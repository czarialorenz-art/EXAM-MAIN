package ui;

import exceptions.AuthenticationException;
import exceptions.DataPersistenceException;
import exceptions.QuizSystemException;
import models.Admin;
import models.Role;
import models.User;
import services.ServiceRegistry;

import java.io.PrintStream;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Application shell: start-up messages, first-run setup, login loop and role-based dispatch. */
public class ConsoleMenu {
    private final ServiceRegistry services;
    private final InputReader in;
    private final PrintStream out;
    private final Map<Role, RoleMenu> menus = new EnumMap<>(Role.class);

    public ConsoleMenu(ServiceRegistry services, InputReader in) {
        this.services = services;
        this.in = in;
        this.out = in.out();
        menus.put(Role.ADMIN, new AdminMenu(services, in));
        menus.put(Role.TEACHER, new TeacherMenu(services, in));
        menus.put(Role.STUDENT, new StudentMenu(services, in));
    }

    public void run() {
        banner();
        List<String> warnings = services.store().getLoadWarnings();
        for (String w : warnings) {
            out.println("[!] " + w);
        }
        int finished = services.sessions().processExpiredSessions();
        if (finished > 0) {
            out.println("[i] " + finished + " exam attempt(s) whose time ran out while the application was closed were finished.");
        }
        try {
            if (services.auth().needsInitialSetup()) {
                initialSetup();
            }
            while (true) {
                out.println();
                out.println("  1. Login");
                out.println("  0. Exit");
                if (in.integer("Select an option: ", 0, 1) == 0) {
                    break;
                }
                login();
            }
        } catch (InputClosedException e) {
            out.println();
            out.println("[i] Input closed.");
        } finally {
            try {
                services.store().saveAll();
            } catch (DataPersistenceException e) {
                out.println("[ERROR] " + e.getMessage());
            }
            out.println("All data saved. Goodbye!");
        }
    }

    private void banner() {
        out.println("=".repeat(72));
        out.println("        ONLINE QUIZ AND EXAMINATION MANAGEMENT SYSTEM");
        out.println("=".repeat(72));
        out.println("  Data folder: " + services.store().getFiles().getDataDir());
    }

    private void initialSetup() {
        out.println();
        out.println("FIRST-TIME SETUP: no accounts exist yet. Create the first administrator account.");
        while (true) {
            try {
                String username = in.required("Admin username: ");
                String fullName = in.required("Full name: ");
                String email = in.line("Email (optional): ");
                String password = in.password("Password (min 8 chars, letters and digits): ");
                String confirm = in.password("Confirm password: ");
                if (!password.equals(confirm)) {
                    out.println("[ERROR] The passwords do not match. Please try again.");
                    continue;
                }
                Admin admin = services.auth().createInitialAdmin(username, password, fullName, email);
                out.println("[OK] Administrator '" + admin.getUsername() + "' created. Please log in.");
                return;
            } catch (QuizSystemException e) {
                out.println("[ERROR] " + e.getMessage());
            }
        }
    }

    private void login() {
        out.println();
        out.println("--- Login ---");
        String username = in.line("Username: ");
        String password = in.password("Password: ");
        try {
            User user = services.auth().login(username, password);
            out.println("[OK] Welcome, " + user.getFullName() + " (" + user.getRole().getLabel() + ").");
            menus.get(user.getRole()).show(user);
            out.println("[i] You have been logged out.");
        } catch (AuthenticationException e) {
            out.println("[ERROR] " + e.getMessage());
        }
    }
}
