package web;

import exceptions.DataPersistenceException;
import exceptions.QuizSystemException;
import services.DemoDataSeeder;
import services.ServiceRegistry;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Entry point of the web version. Uses the same services and data files as the console app.
 * <pre>
 *   java -cp out web.WebMain                       (http://localhost:8080, data in ./data)
 *   java -cp out web.WebMain --demo --port 9000
 *   java -cp out web.WebMain --data mydata --webroot webroot --host 0.0.0.0
 * </pre>
 * The PORT environment variable (set by most hosting services) overrides the default port.
 */
public final class WebMain {
    private WebMain() {
    }

    public static void main(String[] args) {
        Path dataDir = Paths.get("data");
        Path webRoot = Paths.get("webroot");
        String host = "0.0.0.0";
        String envPort = System.getenv("PORT");
        int port = envPort != null && envPort.matches("\\d+") ? Integer.parseInt(envPort) : 8080;
        boolean demo = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--data" -> dataDir = Paths.get(value(args, ++i, "--data"));
                case "--webroot" -> webRoot = Paths.get(value(args, ++i, "--webroot"));
                case "--host" -> host = value(args, ++i, "--host");
                case "--port" -> {
                    String p = value(args, ++i, "--port");
                    if (!p.matches("\\d{1,5}")) {
                        fail("--port needs a number");
                    }
                    port = Integer.parseInt(p);
                }
                case "--demo" -> demo = true;
                case "--help", "-h" -> {
                    System.out.println("Usage: java -cp out web.WebMain [--data <folder>] [--webroot <folder>] "
                            + "[--host <address>] [--port <n>] [--demo]");
                    return;
                }
                default -> fail("Unknown option: " + args[i] + " (use --help)");
            }
        }
        try {
            ServiceRegistry services = ServiceRegistry.open(dataDir, Clock.systemDefaultZone());
            if (demo) {
                System.out.println(DemoDataSeeder.seed(services));
            }
            int finished;
            synchronized (services) {
                finished = services.sessions().processExpiredSessions();
            }
            if (finished > 0) {
                System.out.println(finished + " attempt(s) whose time ran out were finished at start-up.");
            }
            services.store().getLoadWarnings().forEach(w -> System.out.println("Warning: " + w));

            WebServer server = new WebServer(services, webRoot);
            Api.register(server);
            server.start(host, port);

            // Replaces the console's ExamTimer thread: finishes attempts whose time has run out
            // even when the student has closed the browser.
            ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "exam-timer");
                t.setDaemon(true);
                return t;
            });
            timer.scheduleAtFixedRate(() -> {
                try {
                    synchronized (services) {
                        services.sessions().processExpiredSessions();
                    }
                } catch (RuntimeException e) {
                    e.printStackTrace();
                }
            }, 2, 2, TimeUnit.SECONDS);

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                timer.shutdownNow();
                server.stop();
            }));
            System.out.println("Quiz & Examination System is running at http://localhost:" + port + "/");
            System.out.println("Data folder: " + dataDir.toAbsolutePath() + "   (press Ctrl+C to stop)");
        } catch (DataPersistenceException e) {
            fail("Cannot start: " + e.getMessage());
        } catch (QuizSystemException e) {
            fail("Demo data could not be created: " + e.getMessage());
        } catch (IOException e) {
            fail("Cannot open port " + port + ": " + e.getMessage());
        }
    }

    private static String value(String[] args, int i, String option) {
        if (i >= args.length) {
            fail(option + " needs a value");
        }
        return args[i];
    }

    private static void fail(String message) {
        System.err.println(message);
        System.exit(1);
    }
}
