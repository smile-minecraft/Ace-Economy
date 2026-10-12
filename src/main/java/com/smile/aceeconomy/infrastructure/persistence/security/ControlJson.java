package com.smile.aceeconomy.infrastructure.persistence.security;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal, dependency-free JSON reader/writer for the fixed safety-control
 * model. The production persistence foundation pulls no JSON library into
 * the runtime classpath, so this small strict parser covers the control
 * file's fixed schema (objects, arrays, strings, booleans, integers) and
 * rejects corrupt files instead of silently accepting them.
 *
 * <p>The tree is built from plain {@link Map}/{@link List}/{@link String}/
 * {@link Long}/{@link Double}/{@link Boolean}/null. Whole numbers that fit
 * in a long are kept as {@link Long}; anything with a fraction or exponent
 * is kept as {@link Double}.
 */
final class ControlJson {

    private ControlJson() {
    }

    static Object parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("Cannot parse null JSON");
        }
        Parser parser = new Parser(text);
        parser.skipWhitespace();
        Object value = parser.parseValue();
        parser.skipWhitespace();
        if (!parser.isEnd()) {
            throw new IllegalArgumentException(
                    "Trailing content at offset " + parser.pos + " in JSON");
        }
        return value;
    }

    static String render(Object value) {
        StringBuilder out = new StringBuilder();
        renderInto(value, out);
        return out.toString();
    }

    private static void renderInto(Object value, StringBuilder out) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String text) {
            renderString(text, out);
        } else if (value instanceof Boolean flag) {
            out.append(flag ? "true" : "false");
        } else if (value instanceof Long number) {
            out.append(number);
        } else if (value instanceof Integer number) {
            out.append(number);
        } else if (value instanceof Double number) {
            double raw = number;
            if (Double.isNaN(raw) || Double.isInfinite(raw)) {
                throw new IllegalArgumentException("Non-finite JSON number");
            }
            out.append(number);
        } else if (value instanceof Map<?, ?> model) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : model.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                renderString(String.valueOf(entry.getKey()), out);
                out.append(':');
                renderInto(entry.getValue(), out);
            }
            out.append('}');
        } else if (value instanceof List<?> list) {
            out.append('[');
            boolean first = true;
            for (Object entry : list) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                renderInto(entry, out);
            }
            out.append(']');
        } else {
            throw new IllegalArgumentException(
                    "Unsupported JSON value: " + value.getClass().getName());
        }
    }

    private static void renderString(String text, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    private static final class Parser {
        private final String text;
        private int pos;

        Parser(String text) {
            this.text = text;
        }

        boolean isEnd() {
            return pos >= text.length();
        }

        void skipWhitespace() {
            while (!isEnd()) {
                char c = text.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    return;
                }
            }
        }

        Object parseValue() {
            if (isEnd()) {
                throw new IllegalArgumentException("Unexpected end of JSON");
            }
            char c = text.charAt(pos);
            return switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't' -> parseLiteral("true", Boolean.TRUE);
                case 'f' -> parseLiteral("false", Boolean.FALSE);
                case 'n' -> parseLiteral("null", null);
                default -> parseNumber();
            };
        }

        private Map<String, Object> parseObject() {
            expect('{');
            Map<String, Object> model = new LinkedHashMap<>();
            skipWhitespace();
            if (peek('}')) {
                pos++;
                return model;
            }
            while (true) {
                skipWhitespace();
                if (isEnd() || text.charAt(pos) != '"') {
                    throw new IllegalArgumentException(
                            "Expected a string key at offset " + pos);
                }
                String key = parseString();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                model.put(key, parseValue());
                skipWhitespace();
                if (peek(',')) {
                    pos++;
                    continue;
                }
                expect('}');
                return model;
            }
        }

        private List<Object> parseArray() {
            expect('[');
            List<Object> list = new ArrayList<>();
            skipWhitespace();
            if (peek(']')) {
                pos++;
                return list;
            }
            while (true) {
                skipWhitespace();
                list.add(parseValue());
                skipWhitespace();
                if (peek(',')) {
                    pos++;
                    continue;
                }
                expect(']');
                return list;
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (true) {
                if (isEnd()) {
                    throw new IllegalArgumentException("Unterminated string in JSON");
                }
                char c = text.charAt(pos++);
                if (c == '"') {
                    return out.toString();
                }
                if (c == '\\') {
                    if (isEnd()) {
                        throw new IllegalArgumentException("Unterminated escape in JSON");
                    }
                    char esc = text.charAt(pos++);
                    switch (esc) {
                        case '"' -> out.append('"');
                        case '\\' -> out.append('\\');
                        case '/' -> out.append('/');
                        case 'b' -> out.append('\b');
                        case 'f' -> out.append('\f');
                        case 'n' -> out.append('\n');
                        case 'r' -> out.append('\r');
                        case 't' -> out.append('\t');
                        case 'u' -> {
                            if (pos + 4 > text.length()) {
                                throw new IllegalArgumentException("Bad unicode escape in JSON");
                            }
                            try {
                                out.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                            } catch (NumberFormatException badHex) {
                                throw new IllegalArgumentException("Bad unicode escape in JSON");
                            }
                            pos += 4;
                        }
                        default -> throw new IllegalArgumentException(
                                "Bad escape in JSON at offset " + (pos - 1));
                    }
                    continue;
                }
                if (c < 0x20) {
                    throw new IllegalArgumentException("Unescaped control character in JSON");
                }
                out.append(c);
            }
        }

        private Object parseLiteral(String word, Object value) {
            if (text.startsWith(word, pos)) {
                pos += word.length();
                return value;
            }
            throw new IllegalArgumentException("Bad literal at offset " + pos + " in JSON");
        }

        private Object parseNumber() {
            int start = pos;
            if (peek('-')) {
                pos++;
            }
            if (isEnd() || !Character.isDigit(text.charAt(pos))) {
                throw new IllegalArgumentException("Bad number at offset " + start + " in JSON");
            }
            boolean fraction = false;
            while (!isEnd() && Character.isDigit(text.charAt(pos))) {
                pos++;
            }
            if (peek('.')) {
                fraction = true;
                pos++;
                if (isEnd() || !Character.isDigit(text.charAt(pos))) {
                    throw new IllegalArgumentException(
                            "Bad number at offset " + start + " in JSON");
                }
                while (!isEnd() && Character.isDigit(text.charAt(pos))) {
                    pos++;
                }
            }
            if (peek('e') || peek('E')) {
                fraction = true;
                pos++;
                if (peek('+') || peek('-')) {
                    pos++;
                }
                if (isEnd() || !Character.isDigit(text.charAt(pos))) {
                    throw new IllegalArgumentException(
                            "Bad number at offset " + start + " in JSON");
                }
                while (!isEnd() && Character.isDigit(text.charAt(pos))) {
                    pos++;
                }
            }
            String token = text.substring(start, pos);
            try {
                if (!fraction) {
                    return Long.parseLong(token);
                }
                return Double.parseDouble(token);
            } catch (NumberFormatException badNumber) {
                throw new IllegalArgumentException("Bad number at offset " + start + " in JSON");
            }
        }

        private boolean peek(char c) {
            return !isEnd() && text.charAt(pos) == c;
        }

        private void expect(char c) {
            if (isEnd() || text.charAt(pos) != c) {
                throw new IllegalArgumentException(
                        "Expected '" + c + "' at offset " + pos + " in JSON");
            }
            pos++;
        }
    }
}
