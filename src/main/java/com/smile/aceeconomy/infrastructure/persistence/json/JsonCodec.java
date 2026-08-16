package com.smile.aceeconomy.infrastructure.persistence.json;

import com.smile.aceeconomy.ports.persistence.PersistenceException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal, dependency-free JSON reader/writer for the fixed v2 persistence model.
 *
 * <p>Why hand-rolled: the v2 persistence foundation must not pull a JSON library into the
 * production runtime classpath, and the fixed schema (UUIDs, currency ids, exact decimal
 * strings, booleans) needs precise, strict parsing so corrupt/partial backups are detected
 * rather than silently accepted.</p>
 *
 * <p>The tree is built from plain {@link Map}/{@link List}/{@link String}/{@link Double}/
 * {@link Boolean}/null. Numbers are kept as {@link Double}; callers that need exact decimals
 * store them as {@link String} in the model and parse with {@link BigDecimal} themselves.</p>
 */
final class JsonCodec {

    private JsonCodec() {
    }

    // ---------------- parsing ----------------

    static Object parse(String text) {
        if (text == null) {
            throw new PersistenceException("Cannot parse null JSON");
        }
        Parser p = new Parser(text);
        p.skipWhitespace();
        Object value = p.parseValue();
        p.skipWhitespace();
        if (!p.isEnd()) {
            throw new PersistenceException("Trailing content at offset " + p.pos + " in JSON");
        }
        return value;
    }

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
        }

        boolean isEnd() {
            return pos >= s.length();
        }

        void skipWhitespace() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        Object parseValue() {
            if (isEnd()) {
                throw new PersistenceException("Unexpected end of JSON");
            }
            char c = s.charAt(pos);
            switch (c) {
                case '{' -> {
                    return parseObject();
                }
                case '[' -> {
                    return parseArray();
                }
                case '"' -> {
                    return parseString();
                }
                case 't', 'f' -> {
                    return parseBoolean();
                }
                case 'n' -> {
                    return parseNull();
                }
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        return parseNumber();
                    }
                    throw new PersistenceException("Unexpected character '" + c + "' at offset " + pos);
                }
            }
        }

        Map<String, Object> parseObject() {
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                if (peek() != '"') {
                    throw new PersistenceException("Expected object key at offset " + pos);
                }
                String key = parseString();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                Object val = parseValue();
                map.put(key, val);
                skipWhitespace();
                char n = next();
                if (n == '}') {
                    break;
                }
                if (n != ',') {
                    throw new PersistenceException("Expected ',' or '}' at offset " + (pos - 1));
                }
            }
            return map;
        }

        List<Object> parseArray() {
            expect('[');
            List<Object> list = new ArrayList<>();
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                skipWhitespace();
                list.add(parseValue());
                skipWhitespace();
                char n = next();
                if (n == ']') {
                    break;
                }
                if (n != ',') {
                    throw new PersistenceException("Expected ',' or ']' at offset " + (pos - 1));
                }
            }
            return list;
        }

        String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (pos < s.length()) {
                char c = s.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (pos >= s.length()) {
                        throw new PersistenceException("Unterminated escape in JSON string");
                    }
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (pos + 4 > s.length()) {
                                throw new PersistenceException("Invalid unicode escape in JSON");
                            }
                            String hex = s.substring(pos, pos + 4);
                            try {
                                sb.append((char) Integer.parseInt(hex, 16));
                            } catch (NumberFormatException ex) {
                                throw new PersistenceException("Invalid unicode escape: \\u" + hex);
                            }
                            pos += 4;
                        }
                        default -> throw new PersistenceException("Invalid escape '\\" + e + "' in JSON");
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new PersistenceException("Unterminated JSON string");
        }

        Object parseNumber() {
            int start = pos;
            if (peek() == '-') {
                pos++;
            }
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    pos++;
                } else {
                    break;
                }
            }
            String num = s.substring(start, pos);
            try {
                return Double.parseDouble(num);
            } catch (NumberFormatException ex) {
                throw new PersistenceException("Invalid number in JSON: " + num);
            }
        }

        Boolean parseBoolean() {
            if (s.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (s.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            throw new PersistenceException("Invalid literal at offset " + pos);
        }

        Object parseNull() {
            if (s.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            throw new PersistenceException("Invalid literal at offset " + pos);
        }

        char peek() {
            if (pos >= s.length()) {
                throw new PersistenceException("Unexpected end of JSON");
            }
            return s.charAt(pos);
        }

        char next() {
            if (pos >= s.length()) {
                throw new PersistenceException("Unexpected end of JSON");
            }
            return s.charAt(pos++);
        }

        void expect(char c) {
            if (pos >= s.length() || s.charAt(pos) != c) {
                throw new PersistenceException("Expected '" + c + "' at offset " + pos);
            }
            pos++;
        }
    }

    // ---------------- writing ----------------

    @SuppressWarnings("unchecked")
    static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Map) {
            writeObject(sb, (Map<String, Object>) value);
        } else if (value instanceof List) {
            writeArray(sb, (List<Object>) value);
        } else if (value instanceof String) {
            writeString(sb, (String) value);
        } else if (value instanceof Boolean) {
            sb.append(value.toString());
        } else if (value instanceof Number) {
            sb.append(value.toString());
        } else {
            writeString(sb, value.toString());
        }
    }

    private static void writeObject(StringBuilder sb, Map<String, Object> map) {
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            writeString(sb, e.getKey());
            sb.append(':');
            writeValue(sb, e.getValue());
        }
        sb.append('}');
    }

    private static void writeArray(StringBuilder sb, List<Object> list) {
        sb.append('[');
        boolean first = true;
        for (Object o : list) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            writeValue(sb, o);
        }
        sb.append(']');
    }

    private static void writeString(StringBuilder sb, String str) {
        sb.append('"');
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
