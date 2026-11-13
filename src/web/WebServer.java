package web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import exceptions.AccessDeniedException;
import exceptions.AuthenticationException;
import exceptions.QuizSystemException;
import models.User;
import services.ServiceRegistry;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTP front end built on the JDK's own {@code com.sun.net.httpserver}. It serves the static
 * pages in the web root and a JSON API under {@code /api/}. Every API call runs while holding
 * one lock on the {@link ServiceRegistry}, because the services were written for one user at a
 * time; requests are short, so serialising them is simple and safe.
 */
public final class WebServer {
    /** Signals an HTTP error with a status code and a message for the browser. */
    public static final class HttpError extends RuntimeException {
        final int status;

        public HttpError(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    @FunctionalInterface
    public interface Handler {
        Object handle(Ctx ctx) throws QuizSystemException;
    }

    private record Route(String method, Pattern pattern, List<String> names, boolean auth, Handler handler) {
    }

    private record WebSession(String userId, Instant lastSeen) {
    }

    static final String COOKIE = "qsid";
    private static final Duration IDLE_LIMIT = Duration.ofHours(8);
    private static final int MAX_BODY = 1_000_000;

    private final ServiceRegistry services;
    private final Path webRoot;
    private final List<Route> routes = new ArrayList<>();
    private final Map<String, WebSession> sessions = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private HttpServer server;

    public WebServer(ServiceRegistry services, Path webRoot) {
        this.services = services;
        this.webRoot = webRoot.toAbsolutePath().normalize();
    }

    public ServiceRegistry services() {
        return services;
    }

    // ------------------------------------------------------------------ routing

    /** Registers a route that needs a logged-in user. Path segments like {id} become parameters. */
    public void route(String method, String path, Handler h) {
        add(method, path, true, h);
    }

    /** Registers a route that works without logging in. */
    public void open(String method, String path, Handler h) {
        add(method, path, false, h);
    }

    private void add(String method, String path, boolean auth, Handler h) {
        List<String> names = new ArrayList<>();
        Matcher m = Pattern.compile("\\{(\\w+)}").matcher(path);
        StringBuilder regex = new StringBuilder();
        int last = 0;
        while (m.find()) {
            regex.append(Pattern.quote(path.substring(last, m.start()))).append("([^/]+)");
            names.add(m.group(1));
            last = m.end();
        }
        regex.append(Pattern.quote(path.substring(last)));
        routes.add(new Route(method, Pattern.compile(regex.toString()), names, auth, h));
    }

    // ------------------------------------------------------------------ login sessions

    String login(User user) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        sessions.put(token, new WebSession(user.getUserId(), Instant.now()));
        return token;
    }

    void logout(String token) {
        if (token != null) {
            sessions.remove(token);
        }
    }

    /** Ends every browser session of a user (after deactivation or a password reset). */
    void logoutUser(String userId) {
        sessions.values().removeIf(s -> s.userId().equals(userId));
    }

    private User currentUser(String token) {
        if (token == null) {
            return null;
        }
        WebSession s = sessions.get(token);
        if (s == null) {
            return null;
        }
        if (Duration.between(s.lastSeen(), Instant.now()).compareTo(IDLE_LIMIT) > 0) {
            sessions.remove(token);
            return null;
        }
        User u = services.store().findUser(s.userId()).orElse(null);
        if (u == null || !u.isActive()) {
            sessions.remove(token);
            return null;
        }
        sessions.put(token, new WebSession(s.userId(), Instant.now()));
        return u;
    }

    // ------------------------------------------------------------------ server

    public void start(String host, int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/api/", this::handleApi);
        server.createContext("/", this::handleStatic);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
    }

    public void stop() {
        if (server != null) {
            server.stop(1);
        }
    }

    private void handleApi(HttpExchange ex) throws IOException {
        int status = 200;
        Object payload;
        String setCookie = null;
        try {
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath();
            if (!method.equals("GET") && !method.equals("HEAD")) {
                // Cross-site forms cannot send application/json; together with the SameSite
                // cookie this blocks request forgery.
                String type = ex.getRequestHeaders().getFirst("Content-Type");
                if (type == null || !type.toLowerCase().startsWith("application/json")) {
                    throw new HttpError(415, "Requests must be sent as JSON.");
                }
            }
            Route route = null;
            Map<String, String> params = new HashMap<>();
            boolean pathMatched = false;
            for (Route r : routes) {
                Matcher m = r.pattern().matcher(path);
                if (m.matches()) {
                    pathMatched = true;
                    if (r.method().equals(method)) {
                        route = r;
                        for (int i = 0; i < r.names().size(); i++) {
                            params.put(r.names().get(i), URLDecoder.decode(m.group(i + 1), StandardCharsets.UTF_8));
                        }
                        break;
                    }
                }
            }
            if (route == null) {
                throw new HttpError(pathMatched ? 405 : 404, pathMatched ? "Method not allowed." : "Unknown API address.");
            }
            Map<String, Object> body = readBody(ex);
            Map<String, String> query = parseQuery(ex.getRequestURI().getRawQuery());
            String token = cookie(ex, COOKIE);
            synchronized (services) {
                User user = currentUser(token);
                if (route.auth() && user == null) {
                    throw new HttpError(401, "Please log in.");
                }
                Ctx ctx = new Ctx(user, token, params, query, body);
                payload = route.handler().handle(ctx);
            }
            if (payload instanceof LoginResult lr) {
                setCookie = COOKIE + "=" + lr.token() + "; Path=/; HttpOnly; SameSite=Strict"
                        + (isSecure(ex) ? "; Secure" : "");
                payload = lr.body();
            } else if (payload instanceof LogoutResult lr) {
                setCookie = COOKIE + "=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0";
                payload = lr.body();
            }
            if (payload == null) {
                payload = Json.obj("ok", true);
            }
        } catch (HttpError e) {
            status = e.status;
            payload = Json.obj("error", e.getMessage());
        } catch (AuthenticationException e) {
            status = 401;
            payload = Json.obj("error", e.getMessage());
        } catch (AccessDeniedException e) {
            status = 403;
            payload = Json.obj("error", e.getMessage());
        } catch (QuizSystemException | IllegalArgumentException e) {
            status = 400;
            payload = Json.obj("error", e.getMessage());
        } catch (RuntimeException e) {
            e.printStackTrace();
            status = 500;
            payload = Json.obj("error", "Unexpected server error: " + e.getClass().getSimpleName());
        }
        byte[] bytes = Json.write(payload).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        if (setCookie != null) {
            ex.getResponseHeaders().add("Set-Cookie", setCookie);
        }
        send(ex, status, bytes);
    }

    /** Returned by the login handler so the server can set the session cookie. */
    record LoginResult(String token, Object body) {
    }

    /** Returned by the logout handler so the server can clear the session cookie. */
    record LogoutResult(Object body) {
    }

    private static boolean isSecure(HttpExchange ex) {
        String proto = ex.getRequestHeaders().getFirst("X-Forwarded-Proto");
        return "https".equalsIgnoreCase(proto);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            byte[] data = in.readNBytes(MAX_BODY + 1);
            if (data.length > MAX_BODY) {
                throw new HttpError(413, "The request is too large.");
            }
            if (data.length == 0) {
                return null;
            }
            Object parsed = Json.parse(new String(data, StandardCharsets.UTF_8));
            if (parsed != null && !(parsed instanceof Map)) {
                throw new HttpError(400, "The request body must be a JSON object.");
            }
            return (Map<String, Object>) parsed;
        }
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null) {
            return m;
        }
        for (String part : raw.split("&")) {
            int i = part.indexOf('=');
            String k = i < 0 ? part : part.substring(0, i);
            String v = i < 0 ? "" : part.substring(i + 1);
            m.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return m;
    }

    private static String cookie(HttpExchange ex, String name) {
        List<String> headers = ex.getRequestHeaders().get("Cookie");
        if (headers == null) {
            return null;
        }
        for (String h : headers) {
            for (String part : h.split(";")) {
                String p = part.trim();
                if (p.startsWith(name + "=")) {
                    return p.substring(name.length() + 1);
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ static files

    private static final Map<String, String> TYPES = Map.of(
            "html", "text/html; charset=utf-8",
            "css", "text/css; charset=utf-8",
            "js", "text/javascript; charset=utf-8",
            "svg", "image/svg+xml",
            "png", "image/png",
            "ico", "image/x-icon",
            "json", "application/json");

    private void handleStatic(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("GET") && !ex.getRequestMethod().equals("HEAD")) {
            send(ex, 405, "Method not allowed".getBytes(StandardCharsets.UTF_8));
            return;
        }
        String path = URLDecoder.decode(ex.getRequestURI().getPath(), StandardCharsets.UTF_8);
        if (path.equals("/") || path.isEmpty()) {
            path = "/index.html";
        }
        Path file = webRoot.resolve(path.substring(1)).normalize();
        if (!file.startsWith(webRoot) || !Files.isRegularFile(file)) {
            // Unknown paths fall back to the single-page app.
            file = webRoot.resolve("index.html");
            if (!Files.isRegularFile(file)) {
                send(ex, 404, "Not found".getBytes(StandardCharsets.UTF_8));
                return;
            }
        }
        String name = file.getFileName().toString();
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase() : "";
        ex.getResponseHeaders().set("Content-Type", TYPES.getOrDefault(ext, "application/octet-stream"));
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.getResponseHeaders().set("X-Frame-Options", "DENY");
        send(ex, 200, Files.readAllBytes(file));
    }

    private static void send(HttpExchange ex, int status, byte[] bytes) throws IOException {
        if (ex.getRequestMethod().equals("HEAD")) {
            ex.sendResponseHeaders(status, -1);
            ex.close();
            return;
        }
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }
}
