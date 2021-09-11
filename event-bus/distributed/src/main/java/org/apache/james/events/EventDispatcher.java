/****************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one   *
 * or more contributor license agreements.  See the NOTICE file *
 * distributed with this work for additional information        *
 * regarding copyright ownership.  The ASF licenses this file   *
 * to you under the Apache License, Version 2.0 (the            *
 * "License"); you may not use this file except in compliance   *
 * with the License.  You may obtain a copy of the License at   *
 *                                                              *
 *   http://www.apache.org/licenses/LICENSE-2.0                 *
 *                                                              *
 * Unless required by applicable law or agreed to in writing,   *
 * software distributed under the License is distributed on an  *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY       *
 * KIND, either express or implied.  See the License for the    *
 * specific language governing permissions and limitations      *
 * under the License.                                           *
 ****************************************************************/

package org.apache.james.events;

import static com.rabbitmq.client.MessageProperties.PERSISTENT_TEXT_PLAIN;
import static org.apache.james.backends.rabbitmq.Constants.AUTO_DELETE;
import static org.apache.james.backends.rabbitmq.Constants.DIRECT_EXCHANGE;
import static org.apache.james.backends.rabbitmq.Constants.DURABLE;
import static org.apache.james.backends.rabbitmq.Constants.EMPTY_ROUTING_KEY;
import static org.apache.james.backends.rabbitmq.Constants.EXCLUSIVE;
import static org.apache.james.backends.rabbitmq.Constants.NO_ARGUMENTS;
import static org.apache.james.events.RabbitMQEventBus.EVENT_BUS_ID;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;

import org.apache.james.events.RoutingKeyConverter.RoutingKey;
import org.apache.james.util.MDCBuilder;
import org.apache.james.util.MDCStructuredLogger;
import org.apache.james.util.StructuredLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.steveash.guavate.Guavate;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.rabbitmq.client.AMQP;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoProcessor;
import reactor.rabbitmq.BindingSpecification;
import reactor.rabbitmq.ExchangeSpecification;
import reactor.rabbitmq.OutboundMessage;
import reactor.rabbitmq.Sender;
import reactor.util.function.Tuples;

public class EventDispatcher {
    public static class DispatchingFailureGroup extends Group {
        public static DispatchingFailureGroup INSTANCE = new DispatchingFailureGroup();
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(EventDispatcher.class);

    private final NamingStrategy namingStrategy;
    private final EventSerializer eventSerializer;
    private final Sender sender;
    private final LocalListenerRegistry localListenerRegistry;
    private final AMQP.BasicProperties basicProperties;
    private final ListenerExecutor listenerExecutor;
    private final EventDeadLetters deadLetters;

    EventDispatcher(NamingStrategy namingStrategy, EventBusId eventBusId, EventSerializer eventSerializer, Sender sender,
                    LocalListenerRegistry localListenerRegistry,
                    ListenerExecutor listenerExecutor,
                    EventDeadLetters deadLetters) {
        this.namingStrategy = namingStrategy;
        this.eventSerializer = eventSerializer;
        this.sender = sender;
        this.localListenerRegistry = localListenerRegistry;
        this.basicProperties = new AMQP.BasicProperties.Builder()
            .headers(ImmutableMap.of(EVENT_BUS_ID, eventBusId.asString()))
            .deliveryMode(PERSISTENT_TEXT_PLAIN.getDeliveryMode())
            .priority(PERSISTENT_TEXT_PLAIN.getPriority())
            .contentType(PERSISTENT_TEXT_PLAIN.getContentType())
            .build();
        this.listenerExecutor = listenerExecutor;
        this.deadLetters = deadLetters;
    }

    void start() {
        Flux.concat(
            sender.declareExchange(ExchangeSpecification.exchange(namingStrategy.exchange())
                .durable(DURABLE)
                .type(DIRECT_EXCHANGE)),
            sender.declareExchange(ExchangeSpecification.exchange(namingStrategy.deadLetterExchange())
                .durable(DURABLE)
                .type(DIRECT_EXCHANGE)),
            sender.declareQueue(namingStrategy.deadLetterQueue()
                .durable(DURABLE)
                .exclusive(!EXCLUSIVE)
                .autoDelete(!AUTO_DELETE)
                .arguments(NO_ARGUMENTS)),
            sender.bind(BindingSpecification.binding()
                .exchange(namingStrategy.deadLetterExchange())
                .queue(namingStrategy.deadLetterQueue().getName())
                .routingKey(EMPTY_ROUTING_KEY)))
            .then()
            .block();
    }

    Mono<Void> dispatch(Event event, Set<RegistrationKey> keys) {
        return Flux
            .concat(
                dispatchToLocalListeners(event, keys),
                dispatchToRemoteListeners(event, keys))
            .doOnError(throwable -> LOGGER.error("error while dispatching event", throwable))
            .then()
            .subscribeWith(MonoProcessor.create());
    }

    private Mono<Void> dispatchToLocalListeners(Event event, Set<RegistrationKey> keys) {
        return Flux.fromIterable(keys)
            .flatMap(key -> localListenerRegistry.getLocalListeners(key)
                .map(listener -> Tuples.of(key, listener)), EventBus.EXECUTION_RATE)
            .filter(pair -> pair.getT2().getExecutionMode() == EventListener.ExecutionMode.SYNCHRONOUS)
            .flatMap(pair -> executeListener(event, pair.getT2(), pair.getT1()), EventBus.EXECUTION_RATE)
            .then();
    }

    private Mono<Void> executeListener(Event event, EventListener.ReactiveEventListener listener, RegistrationKey registrationKey) {
        return listenerExecutor.execute(listener,
                    MDCBuilder.create()
                        .addContext(EventBus.StructuredLoggingFields.REGISTRATION_KEY, registrationKey),
                    event)
            .onErrorResume(e -> {
                structuredLogger(event, ImmutableSet.of(registrationKey))
                    .log(logger -> logger.error("Exception happens when dispatching event", e));
                return Mono.empty();
            });
    }

    private StructuredLogger structuredLogger(Event event, Set<RegistrationKey> keys) {
        return MDCStructuredLogger.forLogger(LOGGER)
            .addField(EventBus.StructuredLoggingFields.EVENT_ID, event.getEventId())
            .addField(EventBus.StructuredLoggingFields.EVENT_CLASS, event.getClass())
            .addField(EventBus.StructuredLoggingFields.USER, event.getUsername())
            .addField(EventBus.StructuredLoggingFields.REGISTRATION_KEYS, keys);
    }

    private Mono<Void> dispatchToRemoteListeners(Event event, Set<RegistrationKey> keys) {
        return Mono.fromCallable(() -> serializeEvent(event))
            .flatMap(serializedEvent -> Mono.zipDelayError(
                remoteGroupsDispatch(serializedEvent, event),
                remoteKeysDispatch(serializedEvent, keys)))
            .then();
    }

    private Mono<Void> remoteGroupsDispatch(byte[] serializedEvent, Event event) {
        return remoteDispatch(serializedEvent, Collections.singletonList(RoutingKey.empty()))
            .doOnError(ex -> LOGGER.error(
                "cannot dispatch event of type '{}' belonging '{}' with id '{}' to remote groups, store it into dead letter",
                event.getClass().getSimpleName(),
                event.getUsername().asString(),
                event.getEventId().getId(),
                ex))
            .onErrorResume(ex -> deadLetters.store(DispatchingFailureGroup.INSTANCE, event)
                .then(Mono.error(ex)));
    }

    private Mono<Void> remoteKeysDispatch(byte[] serializedEvent, Set<RegistrationKey> keys) {
        return remoteDispatch(serializedEvent,
            keys.stream()
                .map(RoutingKey::of)
                .collect(Guavate.toImmutableList()));
    }

    private Mono<Void> remoteDispatch(byte[] serializedEvent, Collection<RoutingKey> routingKeys) {
        if (routingKeys.isEmpty()) {
            return Mono.empty();
        }
        return sender.send(toMessages(serializedEvent, routingKeys));
    }

    private Flux<OutboundMessage> toMessages(byte[] serializedEvent, Collection<RoutingKey> routingKeys) {
        return Flux.fromIterable(routingKeys)
                .map(routingKey -> new OutboundMessage(namingStrategy.exchange(), routingKey.asString(), basicProperties, serializedEvent));
    }

    private byte[] serializeEvent(Event event) {
        return eventSerializer.toJson(event).getBytes(StandardCharsets.UTF_8);
    }
}
