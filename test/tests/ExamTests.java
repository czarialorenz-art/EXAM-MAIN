package tests;

import exceptions.AccessDeniedException;
import exceptions.InvalidExamException;
import exceptions.ValidationException;
import models.Exam;
import models.ExamSession;
import models.ExamStatus;
import models.MultipleChoiceQuestion;
import models.SchoolClass;
import models.Student;
import models.TimerSettings;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static tests.TestRunner.check;
import static tests.TestRunner.eq;
import static tests.TestRunner.expectThrows;
import static tests.TestRunner.suite;
import static tests.TestRunner.test;

final class ExamTests {
    private ExamTests() {
    }

    static void run() {
        suite("Examination management");
        test("create, add questions, publish -> exam is active and lists the questions", () -> {
            Fixture f = new Fixture();
            MultipleChoiceQuestion q1 = f.mcq("Q1", 0, 2);
            MultipleChoiceQuestion q2 = f.mcq("Q2", 1, 3);
            Exam e = f.exam("Quiz", Fixture.minutes(10, true, false), 1, q1, q2);
            eq(ExamStatus.ACTIVE, e.getEffectiveStatus(LocalDateTime.now(f.clock)), "status");
            eq(2, e.getQuestions().size(), "snapshot size");
            eq(5.0, e.getTotalPoints(), "total points");
        });
        test("publish requires questions and at least one assignee", () -> {
            Fixture f = new Fixture();
            Exam e = f.s.exams().createExam(f.teacher, f.subject.getSubjectId(), "Empty", "");
            expectThrows(InvalidExamException.class, () -> f.s.exams().publish(f.teacher, e.getExamId()));
            f.s.exams().addQuestion(f.teacher, e.getExamId(), f.mcq("Q", 0, 1).getQuestionId());
            expectThrows(InvalidExamException.class, () -> f.s.exams().publish(f.teacher, e.getExamId()));
            f.s.exams().assignStudent(f.teacher, e.getExamId(), f.alice.getUserId());
            f.s.exams().publish(f.teacher, e.getExamId());
            eq(ExamStatus.PUBLISHED, e.getStatus(), "published");
        });
        test("only drafts can be edited; adding the same question twice fails", () -> {
            Fixture f = new Fixture();
            MultipleChoiceQuestion q = f.mcq("Q", 0, 1);
            Exam draft = f.s.exams().createExam(f.teacher, f.subject.getSubjectId(), "D", "");
            f.s.exams().addQuestion(f.teacher, draft.getExamId(), q.getQuestionId());
            expectThrows(InvalidExamException.class, () -> f.s.exams().addQuestion(f.teacher, draft.getExamId(), q.getQuestionId()));
            Exam pub = f.exam("P", Fixture.minutes(5, true, false), 1, q);
            expectThrows(InvalidExamException.class, () -> f.s.exams().updateDetails(f.teacher, pub.getExamId(), "New", "", 50, 1, false, false, false));
        });
        test("invalid settings: duration, passing score, attempts, schedule", () -> {
            expectThrows(InvalidExamException.class, () -> new TimerSettings(true, Duration.ofSeconds(30), true, false));
            expectThrows(InvalidExamException.class, () -> new TimerSettings(true, Duration.ofHours(25), true, false));
            expectThrows(InvalidExamException.class, () -> new TimerSettings(true, null, true, false));
            Fixture f = new Fixture();
            Exam e = f.s.exams().createExam(f.teacher, f.subject.getSubjectId(), "X", "");
            expectThrows(InvalidExamException.class, () -> f.s.exams().updateDetails(f.teacher, e.getExamId(), "X", "", 101, 1, false, false, false));
            expectThrows(InvalidExamException.class, () -> f.s.exams().updateDetails(f.teacher, e.getExamId(), "X", "", 50, 0, false, false, false));
            LocalDateTime now = LocalDateTime.now(f.clock);
            expectThrows(InvalidExamException.class, () -> f.s.exams().setSchedule(f.teacher, e.getExamId(), now, now.minusHours(1)));
            expectThrows(InvalidExamException.class, () -> f.s.exams().createExam(f.teacher, f.subject.getSubjectId(), " ", ""));
        });
        test("supported durations: 5, 10, 15, 30, 45 min, 1h, 1h30, 2h, custom", () -> {
            for (long m : new long[]{5, 10, 15, 30, 45, 60, 90, 120}) {
                new TimerSettings(true, Duration.ofMinutes(m), true, false);
            }
            eq(Duration.ofSeconds(4530), util.TimeFormatter.parseDuration("1h15m30s"), "custom 1h15m30s");
            eq(Duration.ofMinutes(90), util.TimeFormatter.parseDuration("1:30"), "1:30");
            eq(Duration.ofMinutes(45), util.TimeFormatter.parseDuration("45"), "45 minutes");
            expectThrows(ValidationException.class, () -> util.TimeFormatter.parseDuration("abc"));
        });
        test("students cannot access exams that are not assigned to them", () -> {
            Fixture f = new Fixture();
            Student outsider = f.s.users().createStudent(f.admin, "zedd", "Student123", "Zed", "", "S-099", "");
            Exam e = f.exam("Quiz", Fixture.minutes(10, true, false), 1, f.mcq("Q", 0, 1));
            expectThrows(AccessDeniedException.class, () -> f.s.sessions().startOrResume(outsider, e.getExamId()));
            check(f.s.exams().availableExamsFor(outsider).isEmpty(), "not listed");
            check(f.s.exams().availableExamsFor(f.alice).contains(e), "listed for an assigned student");
        });
        test("assignment by class and by individual student", () -> {
            Fixture f = new Fixture();
            Exam e = f.s.exams().createExam(f.teacher, f.subject.getSubjectId(), "Class exam", "");
            f.s.exams().assignClass(f.teacher, e.getExamId(), f.section.getClassId());
            check(f.s.exams().isAssigned(e, f.bob.getUserId()), "class member assigned");
            Student loner = f.s.users().createStudent(f.admin, "loner", "Student123", "Lone", "", "S-050", "");
            expectThrows(ValidationException.class, () -> f.s.exams().assignStudent(f.teacher, e.getExamId(), loner.getUserId()));
            f.s.subjects().enrollStudent(f.teacher, f.subject.getSubjectId(), loner.getUserId());
            f.s.exams().assignStudent(f.teacher, e.getExamId(), loner.getUserId());
            check(f.s.exams().isAssigned(e, loner.getUserId()), "individually assigned");
        });
        test("draft and unpublished exams are hidden from students", () -> {
            Fixture f = new Fixture();
            Exam e = f.s.exams().createExam(f.teacher, f.subject.getSubjectId(), "Hidden", "");
            f.s.exams().setAssignWholeSubject(f.teacher, e.getExamId(), true);
            expectThrows(AccessDeniedException.class, () -> f.s.sessions().startOrResume(f.alice, e.getExamId()));
        });
        test("schedule: not before opening, not after closing", () -> {
            Fixture f = new Fixture();
            MultipleChoiceQuestion q = f.mcq("Q", 0, 1);
            Exam e = f.s.exams().createExam(f.teacher, f.subject.getSubjectId(), "Later", "");
            f.s.exams().addQuestion(f.teacher, e.getExamId(), q.getQuestionId());
            f.s.exams().setAssignWholeSubject(f.teacher, e.getExamId(), true);
            LocalDateTime now = LocalDateTime.now(f.clock);
            f.s.exams().setSchedule(f.teacher, e.getExamId(), now.plusHours(1), now.plusHours(3));
            f.s.exams().publish(f.teacher, e.getExamId());
            expectThrows(InvalidExamException.class, () -> f.s.sessions().startOrResume(f.alice, e.getExamId()));
            f.clock.advance(Duration.ofHours(2));
            f.s.sessions().startOrResume(f.alice, e.getExamId());
            f.clock.advance(Duration.ofHours(2));
            eq(ExamStatus.COMPLETED, e.getEffectiveStatus(LocalDateTime.now(f.clock)), "completed after closing");
            expectThrows(InvalidExamException.class, () -> f.s.sessions().startOrResume(f.bob, e.getExamId()));
        });
        test("maximum attempts is enforced", () -> {
            Fixture f = new Fixture();
            Exam e = f.exam("Two tries", Fixture.minutes(10, true, false), 2, f.mcq("Q", 0, 1));
            for (int i = 0; i < 2; i++) {
                ExamSession s = f.s.sessions().startOrResume(f.alice, e.getExamId());
                f.s.sessions().submit(f.alice, s.getSessionId());
            }
            expectThrows(InvalidExamException.class, () -> f.s.sessions().startOrResume(f.alice, e.getExamId()));
        });
        test("published exams keep their question snapshot when the bank changes", () -> {
            Fixture f = new Fixture();
            MultipleChoiceQuestion q = f.mcq("Before", 0, 1);
            Exam e = f.exam("Snap", Fixture.minutes(10, true, false), 1, q);
            var copy = f.s.questions().getEditableCopy(f.teacher, q.getQuestionId());
            copy.setText("After");
            f.s.questions().updateQuestion(f.teacher, copy);
            eq("Before", e.getQuestions().get(0).getText(), "exam unchanged");
        });
        test("unpublish only before anyone starts; delete only without attempts; archive keeps data", () -> {
            Fixture f = new Fixture();
            Exam e = f.exam("Life", Fixture.minutes(10, true, false), 1, f.mcq("Q", 0, 1));
            f.s.exams().unpublish(f.teacher, e.getExamId());
            f.s.exams().publish(f.teacher, e.getExamId());
            f.s.sessions().startOrResume(f.alice, e.getExamId());
            expectThrows(InvalidExamException.class, () -> f.s.exams().unpublish(f.teacher, e.getExamId()));
            expectThrows(InvalidExamException.class, () -> f.s.exams().delete(f.teacher, e.getExamId()));
            f.s.exams().archive(f.teacher, e.getExamId());
            eq(ExamStatus.ARCHIVED, e.getStatus(), "archived");
            check(!f.s.exams().availableExamsFor(f.alice).contains(e), "hidden from students");
        });
        test("finished exams with attempts can be deleted, then their subject (with its questions)", () -> {
            Fixture f = new Fixture();
            MultipleChoiceQuestion q = f.mcq("Q", 0, 1);
            Exam fin = f.exam("Final examination", Fixture.minutes(10, true, false), 1, q);
            Exam other = f.exam("Pinakamahirap na tanong", Fixture.minutes(10, true, false), 1, q);
            var r1 = f.s.sessions().submit(f.alice, f.s.sessions().startOrResume(f.alice, fin.getExamId()).getSessionId());
            var r2 = f.s.sessions().submit(f.bob, f.s.sessions().startOrResume(f.bob, other.getExamId()).getSessionId());

            expectThrows(InvalidExamException.class, () -> f.s.exams().delete(f.teacher, fin.getExamId()));
            expectThrows(exceptions.AccessDeniedException.class, () -> f.s.exams().delete(f.alice, fin.getExamId()));
            expectThrows(ValidationException.class, () -> f.s.subjects().deleteSubject(f.admin, f.subject.getSubjectId()));

            f.s.exams().close(f.teacher, fin.getExamId());
            f.s.exams().archive(f.teacher, other.getExamId());
            f.s.exams().delete(f.teacher, fin.getExamId());
            f.s.exams().delete(f.teacher, other.getExamId());
            check(f.s.store().getExams().isEmpty(), "both exams deleted");
            check(f.s.store().findResult(r1.getResultId()).isEmpty() && f.s.store().findResult(r2.getResultId()).isEmpty(), "results deleted");
            check(f.s.store().getSessions().isEmpty(), "attempts deleted");

            f.s.subjects().deleteSubject(f.admin, f.subject.getSubjectId());
            check(f.s.store().findSubject(f.subject.getSubjectId()).isEmpty(), "subject deleted");
            check(f.s.store().getQuestions().isEmpty(), "its questions deleted");
            check(f.s.store().findUser(f.alice.getUserId()).isPresent(), "students kept");
            f.reopen();
            check(f.s.store().getExams().isEmpty() && f.s.store().getSubjects().isEmpty(), "still deleted after restart");
        });
        test("a class used only by finished exams can be deleted; results stay unchanged", () -> {
            Fixture f = new Fixture();
            MultipleChoiceQuestion q = f.mcq("Q", 0, 2);
            Exam fin = f.s.exams().createExam(f.teacher, f.subject.getSubjectId(), "Final examination", "");
            f.s.exams().addQuestion(f.teacher, fin.getExamId(), q.getQuestionId());
            f.s.exams().assignClass(f.teacher, fin.getExamId(), f.section.getClassId());
            f.s.exams().publish(f.teacher, fin.getExamId());
            ExamSession s = f.s.sessions().startOrResume(f.alice, fin.getExamId());
            f.s.sessions().saveAnswer(f.alice, s.getSessionId(), q.getQuestionId(), List.of("0"));
            var result = f.s.sessions().submit(f.alice, s.getSessionId());

            Exam draft = f.s.exams().createExam(f.teacher, f.subject.getSubjectId(), "Next quiz", "");
            f.s.exams().assignClass(f.teacher, draft.getExamId(), f.section.getClassId());
            expectThrows(ValidationException.class, () -> f.s.subjects().deleteClass(f.admin, f.section.getClassId()));
            f.s.exams().unassignClass(f.teacher, draft.getExamId(), f.section.getClassId());
            expectThrows(ValidationException.class, () -> f.s.subjects().deleteClass(f.admin, f.section.getClassId()));

            f.s.exams().close(f.teacher, fin.getExamId());
            f.s.subjects().deleteClass(f.admin, f.section.getClassId());
            check(f.s.store().findClass(f.section.getClassId()).isEmpty(), "class deleted");
            check(f.s.store().findUser(f.alice.getUserId()).isPresent(), "students kept");
            check(f.subject.isEnrolled(f.alice.getUserId()), "subject enrolment kept");
            eq(ExamStatus.COMPLETED, fin.getStatus(), "exam still completed");
            eq(2.0, f.s.grading().getResult(result.getResultId()).getTotalScore(), "score unchanged");
            eq(1, f.s.reports().resultsForStudent(f.alice).size(), "student still sees the result");
            check(f.s.exams().assignmentSummary(fin).contains("(deleted class)"), "summary marks the class as deleted");
            f.reopen();
            check(f.s.store().findClass(f.section.getClassId()).isEmpty(), "still deleted after restart");
        });
        test("shuffling is fixed per attempt (same order after resume)", () -> {
            Fixture f = new Fixture();
            List<MultipleChoiceQuestion> qs = List.of(f.mcq("1", 0, 1), f.mcq("2", 0, 1), f.mcq("3", 0, 1), f.mcq("4", 0, 1));
            Exam e = f.s.exams().createExam(f.teacher, f.subject.getSubjectId(), "Shuffle", "");
            for (var q : qs) {
                f.s.exams().addQuestion(f.teacher, e.getExamId(), q.getQuestionId());
            }
            f.s.exams().updateDetails(f.teacher, e.getExamId(), "Shuffle", "", 50, 1, true, true, false);
            f.s.exams().setAssignWholeSubject(f.teacher, e.getExamId(), true);
            f.s.exams().publish(f.teacher, e.getExamId());
            ExamSession s1 = f.s.sessions().startOrResume(f.alice, e.getExamId());
            ExamSession s2 = f.s.sessions().startOrResume(f.alice, e.getExamId());
            eq(s1.getSessionId(), s2.getSessionId(), "same attempt resumed");
            eq(s1.getQuestionOrder(), s2.getQuestionOrder(), "same order");
            eq(4, s1.getQuestionOrder().size(), "all questions present");
        });
        test("a class question is published only in exams assigned to its class", () -> {
            Fixture f = new Fixture();
            SchoolClass other = f.s.subjects().createClass(f.admin, "Section B");
            MultipleChoiceQuestion q = f.mcq("For section A", 0, 1);
            q.setClassId(f.section.getClassId());
            f.s.questions().updateQuestion(f.teacher, q);
            eq(f.section.getClassId(), f.s.questions().getQuestion(q.getQuestionId()).getClassId(), "class saved");
            check(f.s.questions().forClass(f.teacher, f.section.getClassId()).contains(q), "listed under its class");
            check(f.s.questions().forClass(f.teacher, null).isEmpty(), "not listed as general");

            Exam e = f.s.exams().createExam(f.teacher, f.subject.getSubjectId(), "Class quiz", "");
            f.s.exams().addQuestion(f.teacher, e.getExamId(), q.getQuestionId());
            f.s.exams().assignClass(f.teacher, e.getExamId(), other.getClassId());
            expectThrows(InvalidExamException.class, () -> f.s.exams().publish(f.teacher, e.getExamId()));
            f.s.exams().setAssignWholeSubject(f.teacher, e.getExamId(), true);
            expectThrows(InvalidExamException.class, () -> f.s.exams().publish(f.teacher, e.getExamId()));
            f.s.exams().assignClass(f.teacher, e.getExamId(), f.section.getClassId());
            f.s.exams().publish(f.teacher, e.getExamId());
            eq(ExamStatus.PUBLISHED, e.getStatus(), "published once its class is assigned");
            eq(f.section.getClassId(), e.getQuestions().get(0).getClassId(), "snapshot keeps the class");
        });
        test("deleting a class turns its questions into general questions", () -> {
            Fixture f = new Fixture();
            SchoolClass temp = f.s.subjects().createClass(f.admin, "Temporary");
            MultipleChoiceQuestion q = f.mcq("Q", 0, 1);
            q.setClassId(temp.getClassId());
            f.s.questions().updateQuestion(f.teacher, q);
            f.s.subjects().deleteClass(f.admin, temp.getClassId());
            eq(null, f.s.questions().getQuestion(q.getQuestionId()).getClassId(), "class cleared");
            eq(null, f.reopen().questions().getQuestion(q.getQuestionId()).getClassId(), "still cleared after restart");
        });
    }
}
