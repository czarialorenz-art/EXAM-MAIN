package web;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON reader/writer so the web layer needs no external libraries.
 * Objects become {@code Map<String,Object>}, arrays {@code List<Object>}, numbers {@code Double}.
 */
public final class Json {
    private Json() {
    }

    // ------------------------------------------------------------------ writing

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String s) {
            quote(sb, s);
        } else if (v instanceof Boolean b) {
            sb.append(b);
        } else if (v instanceof Double d) {
            if (d.isNaN() || d.isInfinite()) {
                sb.append("null");
            } else if (d == Math.rint(d) && Math.abs(d) < 1e15) {
                sb.append(d.longValue());
            } else {
                sb.append(d);
            }
        } else if (v instanceof Float f) {
            write(sb, f.doubleValue());
        } else if (v instanceof Number n) {
            sb.append(n);
        } else if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                quote(sb, String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof Collection<?> c) {
            sb.append('[');
            boolean first = true;
            for (Object o : c) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                write(sb, o);
            }
            sb.append(']');
        } else if (v instanceof Enum<?> e) {
            quote(sb, e.name());
        } else {
            quote(sb, v.toString());
        }
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '<' -> sb.append("\\u003c");
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

    /** Ordered map builder: {@code Json.obj("a", 1, "b", "x")}. */
    public static Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    // ------------------------------------------------------------------ parsing

    public static Object parse(String text) {
        Parser p = new Parser(text == null ? "" : text);
        p.skip();
        if (p.pos >= p.s.length()) {
            return null;
        }
        Object v = p.value();
        p.skip();
        if (p.pos != p.s.length()) {
            throw new IllegalArgumentException("Unexpected text after JSON value.");
        }
        return v;
    }

    private static final class Parser {
        final String s;
        int pos;

        Parser(String s) {
            this.s = s;
        }

        void skip() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
                pos++;
            }
        }

        Object value() {
            skip();
            if (pos >= s.length()) {
                throw new IllegalArgumentException("Unexpected end of JSON.");
            }
            char c = s.charAt(pos);
            switch (c) {
                case '{':
                    return object();
                case '[':
                    return array();
                case '"':
                    return string();
                case 't':
                    expect("true");
                    return Boolean.TRUE;
                case 'f':
                    expect("false");
                    return Boolean.FALSE;
                case 'n':
                    expect("null");
                    return null;
                default:
                    return number();
            }
        }

        void expect(String word) {
            if (!s.startsWith(word, pos)) {
                throw new IllegalArgumentException("Invalid JSON near position " + pos + ".");
            }
            pos += word.length();
        }

        Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            pos++;
            skip();
            if (peek() == '}') {
                pos++;
                return m;
            }
            while (true) {
                skip();
                if (peek() != '"') {
                    throw new IllegalArgumentException("Expected a property name at position " + pos + ".");
                }
                String key = string();
                skip();
                if (peek() != ':') {
                    throw new IllegalArgumentException("Expected ':' at position " + pos + ".");
                }
                pos++;
                m.put(key, value());
                skip();
                char c = peek();
                pos++;
                if (c == '}') {
                    return m;
                }
                if (c != ',') {
                    throw new IllegalArgumentException("Expected ',' or '}' at position " + (pos - 1) + ".");
                }
            }
        }

        List<Object> array() {
            List<Object> list = new ArrayList<>();
            pos++;
            skip();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(value());
                skip();
                char c = peek();
                pos++;
                if (c == ']') {
                    return list;
                }
                if (c != ',') {
                    throw new IllegalArgumentException("Expected ',' or ']' at position " + (pos - 1) + ".");
                }
            }
        }

        String string() {
            StringBuilder sb = new StringBuilder();
            pos++;
            while (pos < s.length()) {
                char c = s.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    char e = s.charAt(pos++);
                    switch (e) {
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> {
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            pos += 4;
                        }
                        default -> sb.append(e);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalArgumentException("Unterminated string in JSON.");
        }

        Double number() {
            int start = pos;
            while (pos < s.length() && "+-0123456789.eE".indexOf(s.charAt(pos)) >= 0) {
                pos++;
            }
            if (start == pos) {
                throw new IllegalArgumentException("Invalid JSON near position " + pos + ".");
            }
            return Double.parseDouble(s.substring(start, pos));
        }

        char peek() {
            if (pos >= s.length()) {
                throw new IllegalArgumentException("Unexpected end of JSON.");
            }
            return s.charAt(pos);
        }
    }
}
