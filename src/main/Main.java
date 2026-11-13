package main;

import exceptions.DataPersistenceException;
import exceptions.QuizSystemException;
import services.DemoDataSeeder;
import services.ServiceRegistry;
import ui.ConsoleMenu;
import ui.InputReader;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;

/**
 * Entry point. Only parses arguments and wires the application together.
 * <pre>
 *   java -cp out main.Main                 (uses ./data)
 *   java -cp out main.Main --data mydata   (custom data folder)
 *   java -cp out main.Main --demo          (create demonstration records on an empty data folder)
 * </pre>
 */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        Path dataDir = Paths.get("data");
        boolean demo = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--data" -> {
                    if (i + 1 >= args.length) {
                        System.err.println("--data needs a folder name");
                        System.exit(2);
                    }
                    dataDir = Paths.get(args[++i]);
                }
                case "--demo" -> demo = true;
                case "--help", "-h" -> {
                    System.out.println("Usage: java -cp out main.Main [--data <folder>] [--demo]");
                    return;
                }
                default -> {
                    System.err.println("Unknown option: " + args[i] + " (use --help)");
                    System.exit(2);
                }
            }
        }
        try {
            ServiceRegistry services = ServiceRegistry.open(dataDir, Clock.systemDefaultZone());
            if (demo) {
                System.out.println(DemoDataSeeder.seed(services));
            }
            new ConsoleMenu(services, new InputReader(System.in, System.out)).run();
        } catch (DataPersistenceException e) {
            System.err.println("Cannot start: " + e.getMessage());
            System.exit(1);
        } catch (QuizSystemException e) {
            System.err.println("Demo data could not be created: " + e.getMessage());
            System.exit(1);
        }
    }
}
