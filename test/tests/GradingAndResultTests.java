package tests;

import exceptions.AccessDeniedException;
import exceptions.InvalidExamException;
import exceptions.ValidationException;
import models.EssayQuestion;
import models.Exam;
import models.ExamSession;
import models.MultipleChoiceQuestion;
import models.Result;
import models.ResultStatus;
import models.TrueFalseQuestion;
import services.GradingService;

import java.util.List;

import static tests.TestRunner.check;
import static tests.TestRunner.eq;
import static tests.TestRunner.expectThrows;
import static tests.TestRunner.near;
import static tests.TestRunner.suite;
import static tests.TestRunner.test;

final class GradingAndResultTests {
    private GradingAndResultTests() {
    }

    static void run() {
        suite("Grading and results");
        test("objective grading: total, percentage and pass/fail", () -> {
            Fixture f = new Fixture();
            MultipleChoiceQuestion q1 = f.mcq("Q1", 0, 2);
            MultipleChoiceQuestion q2 = f.mcq("Q2", 1, 3);
            TrueFalseQuestion q3 = f.tf("T?", true);
            Exam e = f.exam("Obj", Fixture.minutes(10, true, false), 1, q1, q2, q3);
            ExamSession s = f.s.sessions().startOrResume(f.alice, e.getExamId());
            f.s.sessions().saveAnswer(f.alice, s.getSessionId(), q1.getQuestionId(), List.of("0"));
            f.s.sessions().saveAnswer(f.alice, s.getSessionId(), q2.getQuestionId(), List.of("2"));
            f.s.sessions().saveAnswer(f.alice, s.getSessionId(), q3.getQuestionId(), List.of("true"));
            Result r = f.s.sessions().submit(f.alice, s.getSessionId());
            near(3, r.getTotalScore(), "2 + 0 + 1");
            near(6, r.getMaxScore(), "max");
            near(50, r.getPercentage(), "percent");
            check(!r.isPassed(), "50% < 60% passing -> fail");
            eq(ResultStatus.FINAL, r.getStatus(), "final");
        });
        test("exactly the passing percentage passes", () -> {
            Fixture f = new Fixture();
            MultipleChoiceQuestion a = f.mcq("a", 0, 3);
            MultipleChoiceQuestion b = f.mcq("b", 0, 2);
            Exam e = f.exam("Edge", Fixture.minutes(10, true, false), 1, a, b);
            ExamSession s = f.s.sessions().startOrResume(f.alice, e.getExamId());
            f.s.sessions().saveAnswer(f.alice, s.getSessionId(), a.getQuestionId(), List.of("0"));
            check(f.s.sessions().submit(f.alice, s.getSessionId()).isPassed(), "60% = pass");
        });
        test("essays keep the result PENDING until manually graded; cannot release while pending", () -> {
            Fixture f = new Fixture();
            MultipleChoiceQuestion mc = f.mcq("Q", 0, 2);
            EssayQuestion es = f.essay("Explain", 8);
            Exam e = f.exam("Mixed", Fixture.minutes(10, true, false), 1, mc, es);
            ExamSession s = f.s.sessions().startOrResume(f.alice, e.getExamId());
            f.s.sessions().saveAnswer(f.alice, s.getSessionId(), mc.getQuestionId(), List.of("0"));
            f.s.sessions().saveAnswer(f.alice, s.getSessionId(), es.getQuestionId(), List.of("My essay"));
            Result r = f.s.sessions().submit(f.alice, s.getSessionId());
            eq(ResultStatus.PENDING_MANUAL, r.getStatus(), "pending");
            check(!r.isPassed(), "not marked passed while pending");
            eq(1, f.s.grading().pendingResults(f.teacher).size(), "listed as awaiting grading");
            expectThrows(InvalidExamException.class, () -> f.s.grading().releaseResult(f.teacher, r.getResultId()));
            int[] bulk = f.s.grading().releaseResults(f.teacher, e.getExamId());
            eq(0, bulk[0], "none released");
            eq(1, bulk[1], "one still pending");

            expectThrows(ValidationException.class, () -> f.s.grading().gradeQuestion(f.teacher, r.getResultId(), es.getQuestionId(), 9, ""));
            f.s.grading().gradeQuestion(f.teacher, r.getResultId(), es.getQuestionId(), 6, "Good structure");
            eq(ResultStatus.FINAL, r.getStatus(), "final after grading");
            near(8, r.getTotalScore(), "2 + 6");
            near(80, r.getPercentage(), "80%");
            check(r.isPassed(), "passed");
            eq("Good structure", r.getScore(es.getQuestionId()).getFeedback(), "feedback stored");
            f.s.grading().releaseResult(f.teacher, r.getResultId());
            check(r.isReleased(), "released");
        });
        test("released results are locked until the release is withdrawn", () -> {
            Fixture f = new Fixture();
            MultipleChoiceQuestion mc = f.mcq("Q", 0, 2);
            Exam e = f.exam("Lock", Fixture.minutes(10, true, false), 1, mc);
            ExamSession s = f.s.sessions().startOrResume(f.alice, e.getExamId());
            Result r = f.s.sessions().submit(f.alice, s.getSessionId());
            f.s.grading().releaseResults(f.teacher, e.getExamId());
            expectThrows(ValidationException.class, () -> f.s.grading().gradeQuestion(f.teacher, r.getResultId(), mc.getQuestionId(), 2, ""));
            f.s.grading().withdrawRelease(f.teacher, r.getResultId());
            f.s.grading().gradeQuestion(f.teacher, r.getResultId(), mc.getQuestionId(), 2, "Regraded");
            near(2, r.getTotalScore(), "regraded");
        });
        test("only the owning teacher can grade", () -> {
            Fixture f = new Fixture();
            var other = f.s.users().createTeacher(f.admin, "teacher2", "Teacher123", "Other", "", "");
            MultipleChoiceQuestion mc = f.mcq("Q", 0, 2);
            Exam e = f.exam("Own", Fixture.minutes(10, true, false), 1, mc);
            Result r = f.s.sessions().submit(f.alice, f.s.sessions().startOrResume(f.alice, e.getExamId()).getSessionId());
            expectThrows(AccessDeniedException.class, () -> f.s.grading().gradeQuestion(other, r.getResultId(), mc.getQuestionId(), 1, ""));
            expectThrows(AccessDeniedException.class, () -> f.s.grading().gradeQuestion(f.alice, r.getResultId(), mc.getQuestionId(), 2, ""));
        });
        test("result visibility: students see only their own released results", () -> {
            Fixture f = new Fixture();
            Exam e = f.exam("Vis", Fixture.minutes(10, true, false), 1, f.mcq("Q", 0, 1));
            Result ra = f.s.sessions().submit(f.alice, f.s.sessions().startOrResume(f.alice, e.getExamId()).getSessionId());
            Result rb = f.s.sessions().submit(f.bob, f.s.sessions().startOrResume(f.bob, e.getExamId()).getSessionId());
            check(!GradingService.isVisibleToStudent(ra, f.alice), "not visible before release");
            f.s.grading().releaseResults(f.teacher, e.getExamId());
            check(GradingService.isVisibleToStudent(ra, f.alice), "own released result visible");
            check(!GradingService.isVisibleToStudent(rb, f.alice), "other student's result not visible");
            List<Result> mine = f.s.reports().resultsForStudent(f.alice);
            eq(1, mine.size(), "only own results returned");
            eq(f.alice.getUserId(), mine.get(0).getStudentId(), "owner");
            expectThrows(AccessDeniedException.class, () -> f.s.reports().studentHistory(f.alice, f.bob.getUserId()));
            expectThrows(AccessDeniedException.class, () -> f.s.sessions().requireOwnSession(f.alice, rb.getSessionId()));
        });
        test("auto-release publishes final results immediately", () -> {
            Fixture f = new Fixture();
            MultipleChoiceQuestion q = f.mcq("Q", 0, 1);
            Exam e = f.s.exams().createExam(f.teacher, f.subject.getSubjectId(), "Auto", "");
            f.s.exams().addQuestion(f.teacher, e.getExamId(), q.getQuestionId());
            f.s.exams().updateDetails(f.teacher, e.getExamId(), "Auto", "", 50, 1, false, false, true);
            f.s.exams().setAssignWholeSubject(f.teacher, e.getExamId(), true);
            f.s.exams().publish(f.teacher, e.getExamId());
            Result r = f.s.sessions().submit(f.alice, f.s.sessions().startOrResume(f.alice, e.getExamId()).getSessionId());
            check(r.isReleased(), "released automatically");
        });
        test("unanswered questions score zero", () -> {
            Fixture f = new Fixture();
            Exam e = f.exam("Blank", Fixture.minutes(10, true, false), 1, f.mcq("Q", 0, 4), f.essay("E", 4));
            Result r = f.s.sessions().submit(f.alice, f.s.sessions().startOrResume(f.alice, e.getExamId()).getSessionId());
            near(0, r.getTotalScore(), "zero");
            eq(ResultStatus.FINAL, r.getStatus(), "unanswered essay is not pending");
        });
    }
}
