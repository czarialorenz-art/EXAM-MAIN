package tests;

import models.Admin;
import models.Difficulty;
import models.EssayQuestion;
import models.Exam;
import models.MultipleChoiceQuestion;
import models.Question;
import models.SchoolClass;
import models.Student;
import models.Subject;
import models.Teacher;
import models.TimerSettings;
import models.TrueFalseQuestion;
import services.ServiceRegistry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

/** Fresh, isolated application state in a temporary data folder. */
final class Fixture {
    final Path dir;
    final MutableClock clock;
    ServiceRegistry s;
    final Admin admin;
    final Teacher teacher;
    final Student alice;
    final Student bob;
    final Student carol;
    final Subject subject;
    final SchoolClass section;

    Fixture() throws Exception {
        dir = Files.createTempDirectory("quiz-test");
        clock = new MutableClock(Instant.parse("2026-03-02T08:00:00Z"), ZoneId.of("UTC"));
        s = ServiceRegistry.open(dir, clock);
        admin = s.auth().createInitialAdmin("admin", "Admin123", "Ada Admin", "admin@test.edu");
        teacher = s.users().createTeacher(admin, "teacher1", "Teacher123", "Tom Teacher", "", "CS");
        alice = s.users().createStudent(admin, "alice", "Student123", "Alice Ramos", "", "S-001", "1st Year");
        bob = s.users().createStudent(admin, "bobby", "Student123", "Bob Cruz", "", "S-002", "1st Year");
        carol = s.users().createStudent(admin, "carol", "Student123", "Carol Lim", "", "S-003", "2nd Year");
        subject = s.subjects().createSubject(admin, "CS101", "Intro to Programming", "");
        s.subjects().assignTeacher(admin, subject.getSubjectId(), teacher.getUserId());
        section = s.subjects().createClass(admin, "Section A");
        for (Student st : List.of(alice, bob, carol)) {
            s.subjects().addStudentToClass(admin, section.getClassId(), st.getUserId());
        }
        s.subjects().enrollClassInSubject(admin, section.getClassId(), subject.getSubjectId());
    }

    /** Re-opens the same data folder, like restarting the application. */
    ServiceRegistry reopen() throws Exception {
        s = ServiceRegistry.open(dir, clock);
        return s;
    }

    MultipleChoiceQuestion mcq(String text, int correct, double points) throws Exception {
        MultipleChoiceQuestion q = new MultipleChoiceQuestion(s.questions().nextQuestionId(), teacher.getUserId(),
                subject.getSubjectId(), text, points, "General", Difficulty.EASY, "", List.of("A1", "A2", "A3"), correct);
        s.questions().addQuestion(teacher, q);
        return q;
    }

    TrueFalseQuestion tf(String text, boolean answer) throws Exception {
        TrueFalseQuestion q = new TrueFalseQuestion(s.questions().nextQuestionId(), teacher.getUserId(),
                subject.getSubjectId(), text, 1, "Logic", Difficulty.MEDIUM, "", answer);
        s.questions().addQuestion(teacher, q);
        return q;
    }

    EssayQuestion essay(String text, double points) throws Exception {
        EssayQuestion q = new EssayQuestion(s.questions().nextQuestionId(), teacher.getUserId(),
                subject.getSubjectId(), text, points, "Writing", Difficulty.HARD, "", 0, "");
        s.questions().addQuestion(teacher, q);
        return q;
    }

    /** Creates, configures, assigns (whole subject) and publishes an exam. */
    Exam exam(String title, TimerSettings timer, int attempts, Question... questions) throws Exception {
        Exam e = s.exams().createExam(teacher, subject.getSubjectId(), title, "Do your best.");
        for (Question q : questions) {
            s.exams().addQuestion(teacher, e.getExamId(), q.getQuestionId());
        }
        s.exams().updateDetails(teacher, e.getExamId(), title, "Do your best.", 60, attempts, false, false, false);
        s.exams().setTimer(teacher, e.getExamId(), timer);
        s.exams().setAssignWholeSubject(teacher, e.getExamId(), true);
        s.exams().publish(teacher, e.getExamId());
        return e;
    }

    static TimerSettings minutes(long m, boolean autoSubmit, boolean pause) throws Exception {
        return new TimerSettings(true, Duration.ofMinutes(m), autoSubmit, pause);
    }
}
