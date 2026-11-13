package tests;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal test framework (no JUnit download needed). Run with: ./run.sh test
 * Each suite registers named test cases; failures are collected and the exit code is 1 if any fail.
 */
public final class TestRunner {
    @FunctionalInterface
    public interface TestCase {
        void run() throws Exception;
    }

    private static int passed;
    private static final List<String> failures = new ArrayList<>();

    private TestRunner() {
    }

    public static void suite(String name) {
        System.out.println();
        System.out.println("== " + name + " ==");
    }

    public static void test(String name, TestCase body) {
        try {
            body.run();
            passed++;
            System.out.println("  PASS  " + name);
        } catch (Throwable t) {
            failures.add(name + " -> " + t);
            System.out.println("  FAIL  " + name + "  -> " + t);
        }
    }

    public static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static void eq(Object expected, Object actual, String message) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(message + " (expected " + expected + " but was " + actual + ")");
        }
    }

    public static void near(double expected, double actual, String message) {
        if (Math.abs(expected - actual) > 1e-6) {
            throw new AssertionError(message + " (expected " + expected + " but was " + actual + ")");
        }
    }

    public static <T extends Throwable> T expectThrows(Class<T> type, TestCase body) {
        try {
            body.run();
        } catch (Throwable t) {
            if (type.isInstance(t)) {
                return type.cast(t);
            }
            throw new AssertionError("Expected " + type.getSimpleName() + " but got " + t, t);
        }
        throw new AssertionError("Expected " + type.getSimpleName() + " but nothing was thrown");
    }

    public static void main(String[] args) {
        AuthAndUserTests.run();
        QuestionTests.run();
        ExamTests.run();
        TimerTests.run();
        GradingAndResultTests.run();
        PersistenceTests.run();
        SearchAndReportTests.run();
        System.out.println();
        System.out.println("========================================");
        System.out.println("  " + passed + " passed, " + failures.size() + " failed");
        System.out.println("========================================");
        failures.forEach(f -> System.out.println("  FAILED: " + f));
        System.exit(failures.isEmpty() ? 0 : 1);
    }
}
