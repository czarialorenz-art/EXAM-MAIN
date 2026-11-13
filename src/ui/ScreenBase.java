package ui;

import exceptions.QuizSystemException;
import models.Exam;
import models.Question;
import models.SchoolClass;
import models.Subject;
import models.User;
import services.ServiceRegistry;
import util.TextUtil;
import util.TimeFormatter;

import java.io.PrintStream;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Shared helpers for every console screen: headings, messages, menus and pickers. */
public abstract class ScreenBase {
    protected final ServiceRegistry services;
    protected final InputReader in;
    protected final PrintStream out;

    protected ScreenBase(ServiceRegistry services, InputReader in) {
        this.services = services;
        this.in = in;
        this.out = in.out();
    }

    /** An action that may fail with a user-facing error. */
    @FunctionalInterface
    protected interface Action {
        void run() throws QuizSystemException;
    }

    protected record MenuItem(String label, Action action) {
    }

    protected static MenuItem item(String label, Action action) {
        return new MenuItem(label, action);
    }

    /** Numbered menu loop. Option 0 leaves the menu. Errors are shown and the menu continues. */
    protected void runMenu(String title, String exitLabel, MenuItem... items) {
        while (true) {
            header(title);
            for (int i = 0; i < items.length; i++) {
                out.printf("  %2d. %s%n", i + 1, items[i].label());
            }
            out.printf("  %2d. %s%n", 0, exitLabel);
            int choice = in.integer("Select an option: ", 0, items.length);
            if (choice == 0) {
                return;
            }
            execute(items[choice - 1].action());
        }
    }

    protected void execute(Action action) {
        try {
            action.run();
        } catch (QuizSystemException e) {
            error(e.getMessage());
        } catch (InputClosedException e) {
            throw e;
        } catch (RuntimeException e) {
            error("Unexpected problem (" + e.getClass().getSimpleName() + ": " + e.getMessage()
                    + "). The operation was cancelled; your data is safe.");
        }
    }

    protected void header(String title) {
        out.println();
        out.println("=".repeat(72));
        out.println("  " + title);
        out.println("=".repeat(72));
    }

    protected void sub(String title) {
        out.println();
        out.println("--- " + title + " ---");
    }

    protected void success(String message) {
        out.println("[OK] " + message);
    }

    protected void error(String message) {
        out.println("[ERROR] " + message);
    }

    protected void info(String message) {
        out.println("[i] " + message);
    }

    protected boolean confirm(String prompt) {
        return in.yesNo(prompt);
    }

    /** Lets the user pick one item by number; returns null when cancelled or the list is empty. */
    protected <T> T choose(String title, List<T> options, Function<T, String> label) {
        if (options.isEmpty()) {
            info("Nothing available: " + title.toLowerCase() + ".");
            return null;
        }
        List<T> list = options;
        if (options.size() > 20) {
            String filter = in.line(options.size() + " records. Type text to filter the list (Enter = show all): ");
            if (!filter.isEmpty()) {
                list = options.stream().filter(o -> TextUtil.containsIgnoreCase(label.apply(o), filter))
                        .collect(Collectors.toList());
                if (list.isEmpty()) {
                    info("No records match '" + filter + "'.");
                    return null;
                }
            }
        }
        sub(title);
        for (int i = 0; i < list.size(); i++) {
            out.printf("  %2d. %s%n", i + 1, label.apply(list.get(i)));
        }
        out.println("   0. Cancel");
        int c = in.integer("Choose: ", 0, list.size());
        return c == 0 ? null : list.get(c - 1);
    }

    /** Picks several items: "1,3,5", "2-4" or "all". Returns an empty list when cancelled. */
    protected <T> List<T> chooseMany(String title, List<T> options, Function<T, String> label) {
        if (options.isEmpty()) {
            info("Nothing available: " + title.toLowerCase() + ".");
            return List.of();
        }
        sub(title);
        for (int i = 0; i < options.size(); i++) {
            out.printf("  %2d. %s%n", i + 1, label.apply(options.get(i)));
        }
        while (true) {
            String s = in.line("Enter numbers (e.g. 1,3,5 or 2-4 or 'all'; Enter = cancel): ").toLowerCase();
            if (s.isEmpty()) {
                return List.of();
            }
            if (s.equals("all")) {
                return new ArrayList<>(options);
            }
            try {
                Set<Integer> picked = new LinkedHashSet<>();
                for (String part : s.split("[,\\s]+")) {
                    if (part.isEmpty()) {
                        continue;
                    }
                    if (part.contains("-")) {
                        String[] r = part.split("-");
                        int a = Integer.parseInt(r[0].trim());
                        int b = Integer.parseInt(r[1].trim());
                        for (int i = Math.min(a, b); i <= Math.max(a, b); i++) {
                            picked.add(i);
                        }
                    } else {
                        picked.add(Integer.parseInt(part));
                    }
                }
                List<T> result = new ArrayList<>();
                for (int i : picked) {
                    if (i < 1 || i > options.size()) {
                        throw new NumberFormatException();
                    }
                    result.add(options.get(i - 1));
                }
                return result;
            } catch (RuntimeException e) {
                out.println("  Invalid selection. Use numbers between 1 and " + options.size() + ".");
            }
        }
    }

    // ------------------------------------------------------------------ formatting helpers

    protected String stamp(Instant instant) {
        return TimeFormatter.stamp(instant, services.clock().getZone());
    }

    protected String date(LocalDateTime t) {
        return TimeFormatter.dateTime(t);
    }

    protected LocalDateTime now() {
        return LocalDateTime.now(services.clock());
    }

    protected String userLabel(User u) {
        return u.getFullName() + " (" + u.getUsername() + ", " + u.getUserId() + ") - " + u.getDisplaySummary()
                + (u.isActive() ? "" : " [INACTIVE]");
    }

    protected String subjectLabel(Subject s) {
        return s.getCode() + " - " + s.getName() + "  [teacher: " + services.subjects().teacherName(s) + ", "
                + s.getEnrolledStudentIds().size() + " enrolled]";
    }

    protected String classLabel(SchoolClass c) {
        return c.getName() + " (" + c.getClassId() + ", " + c.getStudentIds().size() + " students)";
    }

    protected String examLabel(Exam e) {
        return e.getTitle() + " (" + e.getExamId() + ", " + services.subjects().subjectLabel(e.getSubjectId()) + ") - "
                + e.getEffectiveStatus(now()).getLabel();
    }

    protected String questionLabel(Question q) {
        return q.getQuestionId() + " [" + q.getType().getLabel() + ", " + TextUtil.num(q.getPoints()) + " pt, "
                + q.getDifficulty().getLabel() + ", " + q.getCategory() + "] " + TextUtil.truncate(q.getText(), 50);
    }

    protected void printUsers(List<? extends User> users) {
        List<String[]> rows = new ArrayList<>();
        for (User u : users) {
            rows.add(new String[]{u.getUserId(), u.getUsername(), u.getFullName(), u.getRole().getLabel(),
                    u.isActive() ? "Active" : "Inactive", u.getDisplaySummary()});
        }
        TablePrinter.print(out, new String[]{"ID", "Username", "Full name", "Role", "Status", "Details"}, rows);
    }

    protected void printExams(List<Exam> exams) {
        List<String[]> rows = new ArrayList<>();
        LocalDateTime now = now();
        for (Exam e : exams) {
            rows.add(new String[]{e.getExamId(), e.getTitle(), services.subjects().subjectLabel(e.getSubjectId()),
                    e.getEffectiveStatus(now).getLabel(), String.valueOf(services.exams().questionCount(e)),
                    TextUtil.num(services.exams().totalPoints(e)), e.getTimer().isEnabled()
                    ? TimeFormatter.hms(e.getTimer().getDuration()) : "none", date(e.getOpensAt()), date(e.getClosesAt())});
        }
        TablePrinter.print(out, new String[]{"ID", "Title", "Subject", "Status", "Items", "Points", "Time",
                "Opens", "Closes"}, rows);
    }

    protected void printQuestions(List<Question> questions) {
        List<String[]> rows = new ArrayList<>();
        for (Question q : questions) {
            rows.add(new String[]{q.getQuestionId(), q.getType().getLabel(), services.subjects().subjectLabel(q.getSubjectId()),
                    q.getCategory(), q.getDifficulty().getLabel(), TextUtil.num(q.getPoints()), q.getText()});
        }
        TablePrinter.print(out, new String[]{"ID", "Type", "Subject", "Category", "Difficulty", "Pts", "Question"}, rows);
    }

    /** Full exam information (used by teachers, admins and the student instruction screen). */
    protected void printExamDetails(Exam e) {
        out.println("  Title          : " + e.getTitle() + " (" + e.getExamId() + ")");
        out.println("  Subject        : " + services.store().findSubject(e.getSubjectId()).map(Subject::toString).orElse("?"));
        out.println("  Status         : " + e.getEffectiveStatus(now()).getLabel()
                + (e.getStatus() != e.getEffectiveStatus(now()) ? " (stored: " + e.getStatus().getLabel() + ")" : ""));
        out.println("  Questions      : " + services.exams().questionCount(e) + "   Total points: "
                + TextUtil.num(services.exams().totalPoints(e)) + "   Passing score: " + TextUtil.pct(e.getPassingPercentage()));
        out.println("  Timer          : " + e.getTimer().describe());
        out.println("  Schedule       : opens " + date(e.getOpensAt()) + ", closes " + date(e.getClosesAt()));
        out.println("  Max attempts   : " + e.getMaxAttempts());
        out.println("  Shuffle        : questions " + TextUtil.yesNo(e.isShuffleQuestions()) + ", choices "
                + TextUtil.yesNo(e.isShuffleChoices()));
        out.println("  Auto-release   : " + (e.isAutoReleaseResults() ? "Yes - final results are shown to students immediately" : "No - the teacher releases results"));
        out.println("  Instructions   : " + (e.getInstructions().isEmpty() ? "(none)" : e.getInstructions()));
    }
}
