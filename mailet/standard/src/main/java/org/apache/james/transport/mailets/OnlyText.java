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

package org.apache.james.transport.mailets;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.HashMap;

import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Multipart;
import javax.mail.Part;
import javax.mail.internet.ContentType;

import org.apache.mailet.Experimental;
import org.apache.mailet.Mail;
import org.apache.mailet.MailetException;
import org.apache.mailet.base.GenericMailet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keep only the text part of a message.
 * <p>If the message is text only then it doesn't touch it, if it is a multipart it
 * transform it a in plain text message with the first text part found.<br>
 * - text/plain<br>
 * - text/html => with a conversion to text only<br>
 * - text/* as is.</p>
 */
@Experimental
public class OnlyText extends GenericMailet {
    private static final Logger LOGGER = LoggerFactory.getLogger(OnlyText.class);

    private static final String PARAMETER_NAME_NOTEXT_PROCESSOR = "NoTextProcessor";

    private String optionsNotextProcessor = null;
    private final HashMap<String, String> charMap = new HashMap<>();

    @Override
    public String getMailetInfo() {
        return "OnlyText";
    }

    @Override
    public void init() throws MailetException {
        optionsNotextProcessor = getInitParameter(PARAMETER_NAME_NOTEXT_PROCESSOR);
        initEntityTable();
    }

    private int[] process(Mail mail, Multipart mp, int found, int htmlPart, int stringPart) throws MessagingException, IOException {
        for (int i = 0; found < 0 && i < mp.getCount(); i++) {
            Object content = null;
            try {
                content = mp.getBodyPart(i).getContent();
            } catch (UnsupportedEncodingException e) {
                LOGGER.error("Caught error in a text/plain part, skipping...", e);
            }
            if (content != null) {
                if (mp.getBodyPart(i).isMimeType("text/plain")) {
                    setContentFromPart(mail.getMessage(), mp.getBodyPart(i), null, false);
                    found = 1;
                } else if (htmlPart == -1 && mp.getBodyPart(i).isMimeType("text/html")) {
                    htmlPart = i;
                } else if (stringPart == -1 && content instanceof String) {
                    stringPart = i;
                } else if (content instanceof Multipart) {
                    int[] res = process(mail, (Multipart) content, found, htmlPart, stringPart);
                    found = res[0];
                    htmlPart = res[1];
                    stringPart = res[2];
                }
            }
        }

        return new int[]{found, htmlPart, stringPart};

    }

    @Override
    public void service(Mail mail) throws MailetException {
        try {
            Object content = mail.getMessage().getContent();
            if (content instanceof Multipart) {
                Multipart mp = (Multipart) content;

                int found = -1;
                int htmlPart = -1;
                int stringPart = -1;
                int[] res = process(mail, (Multipart) content, found, htmlPart, stringPart);
                found = res[0];
                htmlPart = res[1];
                stringPart = res[2];

                if (found < 0 && htmlPart != -1) {
                    setContentFromPart(mail.getMessage(), mp.getBodyPart(htmlPart), html2Text((String) mp.getBodyPart(htmlPart).getContent()), true);
                    found = 1;
                }

                if (found < 0 && stringPart != -1) {
                    setContentFromPart(mail.getMessage(), mp.getBodyPart(htmlPart), null, false);
                    found = 1;
                }


                if (found < 0 && optionsNotextProcessor != null) {
                    mail.setState(optionsNotextProcessor);
                }

            } else if (!(content instanceof String) && optionsNotextProcessor != null) {
                mail.setState(optionsNotextProcessor);
            } else if (mail.getMessage().isMimeType("text/html")) {
                setContentFromPart(mail.getMessage(), mail.getMessage(), html2Text((String) mail.getMessage().getContent()), true);
            }

        } catch (IOException | MessagingException e) {
            throw new MailetException("Failed fetching text part", e);
        }
    }

    private static void setContentFromPart(Message m, Part p, String newText, boolean setTextPlain) throws MessagingException, IOException {
        String contentType = p.getContentType();
        if (setTextPlain) {
            ContentType ct = new ContentType(contentType);
            ct.setPrimaryType("text");
            ct.setSubType("plain");
            contentType = ct.toString();
        }
        m.setContent(newText != null ? newText : p.getContent(), contentType);
        String[] h = p.getHeader("Content-Transfer-Encoding");
        if (h != null && h.length > 0) {
            m.setHeader("Content-Transfer-Encoding", h[0]);
        }
        m.saveChanges();
    }

    public String html2Text(String html) {
        return decodeEntities(html
                .replaceAll("\\<([bB][rR]|[dD][lL])[ ]*[/]*[ ]*\\>", "\n")
                .replaceAll("\\</([pP]|[hH]5|[dD][tT]|[dD][dD]|[dD][iI][vV])[ ]*\\>", "\n")
                .replaceAll("\\<[lL][iI][ ]*[/]*[ ]*\\>", "\n* ")
                .replaceAll("\\<[dD][dD][ ]*[/]*[ ]*\\>", " - ")
                .replaceAll("\\<.*?\\>", ""));
    }

    public String decodeEntities(String data) {
        StringBuffer buffer = new StringBuffer();
        StringBuilder res = new StringBuilder();
        int lastAmp = -1;
        for (int i = 0; i < data.length(); i++) {
            char c = data.charAt(i);

            if (c == '&' && lastAmp == -1) {
                lastAmp = buffer.length();
            } else if (c == ';' && (lastAmp > -1)) { // && (lastAmp > (buffer.length() - 7))) { // max: &#xxxx;
                if (charMap.containsKey(buffer.toString())) {
                    res.append(charMap.get(buffer.toString()));
                } else {
                    res.append("&").append(buffer.toString()).append(";");
                }
                lastAmp = -1;
                buffer = new StringBuffer();
            } else if (lastAmp == -1) {
                res.append(c);
            } else {
                buffer.append(c);
            }
        }
        return res.toString();
    }

    private void initEntityTable() {
        for (int index = 11; index < 32; index++) {
            charMap.put("#0" + index, String.valueOf((char) index));
        }
        for (int index = 32; index < 128; index++) {
            charMap.put("#" + index, String.valueOf((char) index));
        }
        for (int index = 128; index < 256; index++) {
            charMap.put("#" + index, String.valueOf((char) index));
        }

        // A complete reference is here:
        // http://en.wikipedia.org/wiki/List_of_XML_and_HTML_character_entity_references

        charMap.put("#09", "\t");
        charMap.put("#10", "\n");
        charMap.put("#13", "\r");
        charMap.put("#60", "<");
        charMap.put("#62", ">");

        charMap.put("lt", "<");
        charMap.put("gt", ">");
        charMap.put("amp", "&");
        charMap.put("nbsp", " ");
        charMap.put("quot", "\"");

        charMap.put("Ouml", "??");
        charMap.put("Oacute", "??");
        charMap.put("iquest", "??");
        charMap.put("yuml", "??");
        charMap.put("cent", "??");
        charMap.put("deg", "??");
        charMap.put("aacute", "??");
        charMap.put("uuml", "??");
        charMap.put("Otilde", "??");
        charMap.put("Iacute", "??");
        charMap.put("frac12", "??");
        charMap.put("atilde", "??");
        charMap.put("ordf", "??");
        charMap.put("sup2", "??");
        charMap.put("sup3", "??");
        charMap.put("frac14", "??");
        charMap.put("ucirc", "??");
        charMap.put("brvbar", "??");
        charMap.put("reg", "??");
        charMap.put("sup1", "??");
        charMap.put("THORN", "??");
        charMap.put("ordm", "??");
        charMap.put("eth", "??");
        charMap.put("Acirc", "??");
        charMap.put("aring", "??");
        charMap.put("Uacute", "??");
        charMap.put("oslash", "??");
        charMap.put("eacute", "??");
        charMap.put("agrave", "??");
        charMap.put("Ecirc", "??");
        charMap.put("laquo", "??");
        charMap.put("Igrave", "??");
        charMap.put("Agrave", "??");
        charMap.put("macr", "??");
        charMap.put("Ucirc", "??");
        charMap.put("igrave", "??");
        charMap.put("ouml", "??");
        charMap.put("iexcl", "??");
        charMap.put("otilde", "??");
        charMap.put("ugrave", "??");
        charMap.put("Aring", "??");
        charMap.put("Ograve", "??");
        charMap.put("Ugrave", "??");
        charMap.put("ograve", "??");
        charMap.put("acute", "??");
        charMap.put("ecirc", "??");
        charMap.put("euro", "???");
        charMap.put("uacute", "??");
        charMap.put("shy", "\\u00AD");
        charMap.put("cedil", "??");
        charMap.put("raquo", "??");
        charMap.put("Atilde", "??");
        charMap.put("Iuml", "??");
        charMap.put("iacute", "??");
        charMap.put("ocirc", "??");
        charMap.put("curren", "??");
        charMap.put("frac34", "??");
        charMap.put("Euml", "??");
        charMap.put("szlig", "??");
        charMap.put("pound", "??");
        charMap.put("not", "??");
        charMap.put("AElig", "??");
        charMap.put("times", "??");
        charMap.put("Aacute", "??");
        charMap.put("Icirc", "??");
        charMap.put("para", "??");
        charMap.put("uml", "??");
        charMap.put("oacute", "??");
        charMap.put("copy", "??");
        charMap.put("Eacute", "??");
        charMap.put("Oslash", "??");
        charMap.put("divid", "??");
        charMap.put("aelig", "??");
        charMap.put("euml", "??");
        charMap.put("Ocirc", "??");
        charMap.put("yen", "??");
        charMap.put("ntilde", "??");
        charMap.put("Ntilde", "??");
        charMap.put("thorn", "??");
        charMap.put("yacute", "??");
        charMap.put("Auml", "??");
        charMap.put("Yacute", "??");
        charMap.put("ccedil", "??");
        charMap.put("micro", "??");
        charMap.put("Ccedil", "??");
        charMap.put("sect", "??");
        charMap.put("icirc", "??");
        charMap.put("middot", "??");
        charMap.put("Uuml", "??");
        charMap.put("ETH", "??");
        charMap.put("egrave", "??");
        charMap.put("iuml", "??");
        charMap.put("plusmn", "??");
        charMap.put("acirc", "??");
        charMap.put("auml", "??");
        charMap.put("Egrave", "??");
    }
}
