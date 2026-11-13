package tests;

import data.DataStore;
import data.FileManager;
import models.Exam;
import models.ExamSession;
import models.Result;
import services.ServiceRegistry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.stream.Stream;

import static tests.TestRunner.check;
import static tests.TestRunner.eq;
import static tests.TestRunner.suite;
import static tests.TestRunner.test;

final class PersistenceTests {
    private PersistenceTests() {
    }

    static void run() {
        suite("File persistence");
        test("all record types survive closing and reopening the application", () -> {
            Fixture f = new Fixture();
            var q = f.mcq("Persist?", 1, 2);
            Exam e = f.exam("Saved exam", Fixture.minutes(10, true, false), 1, q);
            ExamSession s = f.s.sessions().startOrResume(f.alice, e.getExamId());
            f.s.sessions().saveAnswer(f.alice, s.getSessionId(), q.getQuestionId(), List.of("1"));
            Result r = f.s.sessions().submit(f.alice, s.getSessionId());

            ServiceRegistry again = f.reopen();
            eq(5, again.store().userCount(), "users");
            eq(1, again.store().getSubjects().size(), "subjects");
            eq(1, again.store().getClasses().size(), "classes");
            eq(3, again.store().getClasses().iterator().next().getStudentIds().size(), "class members");
            eq(1, again.store().getQuestions().size(), "questions");
            eq("Saved exam", again.store().findExam(e.getExamId()).get().getTitle(), "exam");
            eq(List.of("1"), again.store().findSession(s.getSessionId()).get().getAnswer(q.getQuestionId()).getValues(), "answers");
            eq(r.getTotalScore(), again.store().findResult(r.getResultId()).get().getTotalScore(), "result");
            check(again.auth().login("alice", "Student123") != null, "login after restart");
            check(again.store().getLoadWarnings().isEmpty(), "no warnings: " + again.store().getLoadWarnings());
        });
        test("new IDs continue after a restart (no duplicates)", () -> {
            Fixture f = new Fixture();
            f.reopen();
            var s = f.s.users().createStudent(f.s.store().findUser(f.admin.getUserId()).get(), "danny", "Student123", "Dan", "", "S-004", "");
            eq("STU-0004", s.getUserId(), "next student id");
        });
        test("missing data folder/files start empty without errors", () -> {
            Path dir = Files.createTempDirectory("quiz-empty").resolve("not-yet-created");
            ServiceRegistry s = ServiceRegistry.open(dir, Clock.systemUTC());
            check(s.auth().needsInitialSetup(), "first run");
            check(s.store().getLoadWarnings().isEmpty(), "no warnings on first run");
            check(Files.isDirectory(dir), "folder created");
        });
        test("corrupted file is quarantined and recovered from its backup", () -> {
            Fixture f = new Fixture();
            f.s.users().createStudent(f.admin, "extra", "Student123", "Extra", "", "S-010", "");
            Files.writeString(f.dir.resolve("users.dat"), "this is not a valid data file");
            ServiceRegistry again = f.reopen();
            check(again.store().userCount() >= 5, "users recovered from users.dat.bak (" + again.store().userCount() + ")");
            check(again.store().getLoadWarnings().stream().anyMatch(w -> w.contains("restored from its backup")), "warning shown");
            try (Stream<Path> files = Files.list(f.dir)) {
                check(files.anyMatch(p -> p.getFileName().toString().startsWith("users.dat.corrupt-")), "damaged file kept");
            }
        });
        test("corrupted file without backup starts empty and warns, other files still load", () -> {
            Fixture f = new Fixture();
            Files.writeString(f.dir.resolve("subjects.dat"), "garbage");
            Files.deleteIfExists(f.dir.resolve("subjects.dat.bak"));
            ServiceRegistry again = f.reopen();
            eq(0, again.store().getSubjects().size(), "subjects empty");
            eq(5, again.store().userCount(), "users unaffected");
            check(again.store().getLoadWarnings().stream().anyMatch(w -> w.contains("starting with no records")), "warning");
        });
        test("saving leaves no temp file and keeps a .bak copy of the previous version", () -> {
            Fixture f = new Fixture();
            f.s.store().saveUsers();
            f.s.store().saveUsers();
            check(Files.exists(f.dir.resolve("users.dat")), "data file");
            check(Files.exists(f.dir.resolve("users.dat.bak")), "backup");
            check(!Files.exists(f.dir.resolve("users.dat.tmp")), "no temp file");
        });
        test("a file containing an unexpected type is treated as corrupted", () -> {
            Path dir = Files.createTempDirectory("quiz-type");
            FileManager fm = new FileManager(dir);
            fm.save(DataStore.EXAMS, new java.util.HashMap<String, String>());
            DataStore store = new DataStore(fm);
            store.loadAll();
            eq(0, store.getExams().size(), "loaded as empty");
            check(!store.getLoadWarnings().isEmpty(), "warned");
        });
        test("backup and text report export write files", () -> {
            Fixture f = new Fixture();
            f.s.store().saveAll();
            Path backup = f.s.store().getFiles().backupAll();
            check(Files.exists(backup.resolve("users.dat")), "backup copy");
            Path report = f.s.reports().export(f.admin, "summary", f.s.reports().systemSummary(f.admin));
            check(Files.readString(report).contains("SYSTEM SUMMARY"), "report content");
        });
    }
}
