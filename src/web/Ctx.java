package web;

import exceptions.ValidationException;
import models.User;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** One API request: the logged-in user, path parameters, query string and JSON body. */
public final class Ctx {
    private final User user;
    private final String token;
    private final Map<String, String> params;
    private final Map<String, String> query;
    private final Map<String, Object> body;

    Ctx(User user, String token, Map<String, String> params, Map<String, String> query, Map<String, Object> body) {
        this.user = user;
        this.token = token;
        this.params = params;
        this.query = query;
        this.body = body == null ? Collections.emptyMap() : body;
    }

    public User user() {
        return user;
    }

    String token() {
        return token;
    }

    /** Path parameter such as {id}. */
    public String param(String name) {
        return params.get(name);
    }

    /** Query-string value, or null when missing or blank. */
    public String query(String name) {
        String v = query.get(name);
        return v == null || v.isBlank() ? null : v;
    }

    public Map<String, Object> body() {
        return body;
    }

    // ------------------------------------------------------------------ typed body access

    /** String field; null when missing. Numbers are converted to text. */
    public String str(String key) {
        return asString(body.get(key));
    }

    /** String field that must be present and non-blank. */
    public String req(String key, String label) throws ValidationException {
        String v = str(key);
        if (v == null || v.isBlank()) {
            throw new ValidationException(label + " is required.");
        }
        return v;
    }

    /** String field, or null when missing/blank (used for optional ids). */
    public String optId(String key) {
        String v = str(key);
        return v == null || v.isBlank() ? null : v;
    }

    public boolean bool(String key) {
        return asBool(body.get(key));
    }

    public double num(String key, String label) throws ValidationException {
        return asNum(body.get(key), label);
    }

    public int integer(String key, String label) throws ValidationException {
        double d = num(key, label);
        if (d != Math.rint(d)) {
            throw new ValidationException(label + " must be a whole number.");
        }
        return (int) d;
    }

    public List<String> strList(String key) {
        return asStringList(body.get(key));
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> map(String key) {
        Object v = body.get(key);
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : Collections.emptyMap();
    }

    // ------------------------------------------------------------------ static conversions

    static String asString(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Double d && d == Math.rint(d)) {
            return String.valueOf(d.longValue());
        }
        return v.toString();
    }

    static boolean asBool(Object v) {
        return v instanceof Boolean b ? b : v != null && "true".equalsIgnoreCase(v.toString());
    }

    static double asNum(Object v, String label) throws ValidationException {
        if (v instanceof Double d) {
            return d;
        }
        if (v instanceof String s && !s.isBlank()) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException e) {
                // fall through
            }
        }
        throw new ValidationException(label + " must be a number.");
    }

    static List<String> asStringList(Object v) {
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> list) {
            for (Object o : list) {
                out.add(o == null ? "" : asString(o));
            }
        }
        return out;
    }
}
