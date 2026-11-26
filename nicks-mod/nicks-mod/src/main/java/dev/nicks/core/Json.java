package dev.nicks.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small dependency-free JSON reader/writer.
 * Objects become LinkedHashMap, arrays ArrayList, integers Long, other numbers Double.
 * Kept free of Gson so the whole core package can be compiled and unit-tested without Minecraft.
 */
public final class Json {
    private static final int MAX_DEPTH = 64;

    private Json() {}

    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.skipWs();
        Object v = p.value(0);
        p.skipWs();
        if (p.i != text.length()) throw p.error("Trailing characters");
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object o = parse(text);
        if (o instanceof Map<?, ?> m) return (Map<String, Object>) m;
        throw new IllegalArgumentException("Expected a JSON object");
    }

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, 0);
        return sb.append('\n').toString();
    }

    // ---- typed accessors -------------------------------------------------

    public static String str(Map<String, Object> m, String k, String def) {
        return m != null && m.get(k) instanceof String s ? s : def;
    }

    public static long num(Map<String, Object> m, String k, long def) {
        return m != null && m.get(k) instanceof Number n ? n.longValue() : def;
    }

    public static boolean bool(Map<String, Object> m, String k, boolean def) {
        return m != null && m.get(k) instanceof Boolean b ? b : def;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Map<String, Object> m, String k) {
        return m != null && m.get(k) instanceof Map<?, ?> o ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Map<String, Object> m, String k) {
        return m != null && m.get(k) instanceof List<?> l ? (List<Object>) l : List.of();
    }

    // ---- writer ----------------------------------------------------------

    private static void write(StringBuilder sb, Object v, int ind) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String s) {
            quote(sb, s);
        } else if (v instanceof Boolean || v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte) {
            sb.append(v);
        } else if (v instanceof Number n) {
            double d = n.doubleValue();
            if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) sb.append((long) d);
            else sb.append(d);
        } else if (v instanceof Enum<?> e) {
            quote(sb, e.name());
        } else if (v instanceof Map<?, ?> m) {
            if (m.isEmpty()) { sb.append("{}"); return; }
            sb.append("{\n");
            int n = 0;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                indent(sb, ind + 1);
                quote(sb, String.valueOf(e.getKey()));
                sb.append(": ");
                write(sb, e.getValue(), ind + 1);
                if (++n < m.size()) sb.append(',');
                sb.append('\n');
            }
            indent(sb, ind);
            sb.append('}');
        } else if (v instanceof Collection<?> c) {
            if (c.isEmpty()) { sb.append("[]"); return; }
            sb.append("[\n");
            int n = 0;
            for (Object o : c) {
                indent(sb, ind + 1);
                write(sb, o, ind + 1);
                if (++n < c.size()) sb.append(',');
                sb.append('\n');
            }
            indent(sb, ind);
            sb.append(']');
        } else {
            throw new IllegalArgumentException("Unsupported JSON type " + v.getClass().getName());
        }
    }

    private static void indent(StringBuilder sb, int n) {
        for (int i = 0; i < n; i++) sb.append("  ");
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
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    // ---- parser ----------------------------------------------------------

    private static final class Parser {
        final String s;
        int i;

        Parser(String s) { this.s = s; }

        IllegalArgumentException error(String msg) {
            return new IllegalArgumentException(msg + " at position " + i);
        }

        void skipWs() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        boolean peek(char c) {
            return i < s.length() && s.charAt(i) == c;
        }

        Object value(int depth) {
            if (depth > MAX_DEPTH) throw error("Nesting too deep");
            if (i >= s.length()) throw error("Unexpected end of input");
            switch (s.charAt(i)) {
                case '{': return object(depth);
                case '[': return array(depth);
                case '"': return string();
                case 't': expect("true"); return Boolean.TRUE;
                case 'f': expect("false"); return Boolean.FALSE;
                case 'n': expect("null"); return null;
                default: return number();
            }
        }

        void expect(String word) {
            if (!s.startsWith(word, i)) throw error("Unexpected token");
            i += word.length();
        }

        Map<String, Object> object(int depth) {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            skipWs();
            if (peek('}')) { i++; return m; }
            while (true) {
                skipWs();
                if (!peek('"')) throw error("Expected string key");
                String k = string();
                skipWs();
                if (!peek(':')) throw error("Expected ':'");
                i++;
                skipWs();
                m.put(k, value(depth + 1));
                skipWs();
                if (peek(',')) { i++; continue; }
                if (peek('}')) { i++; return m; }
                throw error("Expected ',' or '}'");
            }
        }

        List<Object> array(int depth) {
            List<Object> l = new ArrayList<>();
            i++;
            skipWs();
            if (peek(']')) { i++; return l; }
            while (true) {
                skipWs();
                l.add(value(depth + 1));
                skipWs();
                if (peek(',')) { i++; continue; }
                if (peek(']')) { i++; return l; }
                throw error("Expected ',' or ']'");
            }
        }

        String string() {
            StringBuilder sb = new StringBuilder();
            i++;
            while (true) {
                if (i >= s.length()) throw error("Unterminated string");
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c != '\\') { sb.append(c); continue; }
                if (i >= s.length()) throw error("Bad escape");
                char e = s.charAt(i++);
                switch (e) {
                    case '"', '\\', '/' -> sb.append(e);
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (i + 4 > s.length()) throw error("Bad unicode escape");
                        try {
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        } catch (NumberFormatException ex) {
                            throw error("Bad unicode escape");
                        }
                        i += 4;
                    }
                    default -> throw error("Bad escape");
                }
            }
        }

        Object number() {
            int st = i;
            if (peek('-')) i++;
            while (i < s.length() && "0123456789+-.eE".indexOf(s.charAt(i)) >= 0) i++;
            String t = s.substring(st, i);
            if (t.isEmpty() || t.equals("-")) throw error("Unexpected token");
            try {
                if (t.indexOf('.') < 0 && t.indexOf('e') < 0 && t.indexOf('E') < 0) return Long.parseLong(t);
                return Double.parseDouble(t);
            } catch (NumberFormatException e) {
                throw error("Bad number");
            }
        }
    }
}
