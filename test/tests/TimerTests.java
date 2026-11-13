package tests;

import exceptions.DuplicateSubmissionException;
import exceptions.ExamTimeExpiredException;
import exceptions.InvalidExamException;
import models.Exam;
import models.ExamSession;
import models.MultipleChoiceQuestion;
import models.SessionState;
import models.SubmissionType;
import models.TimerSettings;
import services.ServiceRegistry;
import ui.ExamTimer;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static tests.TestRunner.check;
import static tests.TestRunner.eq;
import static tests.TestRunner.expectThrows;
import static tests.TestRunner.suite;
import static tests.TestRunner.test;

final class TimerTests {
    private TimerTests() {
    }

    static void run() {
        suite("Examination timer");
        test("deadline = start + configured duration; remaining counts down", () -> {
            Fixture f = new Fixture();
            Exam e = f.exam("T", Fixture.minutes(30, true, false), 1, f.mcq("Q", 0, 1));
            ExamSession s = f.s.sessions().startOrResume(f.alice, e.getExamId());
            eq(s.getStartedAt().plus(Duration.ofMinutes(30)), s.getDeadline(), "deadline");
            f.clock.advance(Duration.ofMinutes(12));
            eq(Duration.ofMinutes(18), s.remaining(f.clock.instant()), "remaining");
        });
        test("custom duration 1h 30m 15s", () -> {
            Fixture f = new Fixture();
            Exam e = f.exam("T", new TimerSettings(true, Duration.ofSeconds(5415), true, false), 1, f.mcq("Q", 0, 1));
            ExamSession s = f.s.sessions().startOrResume(f.alice, e.getExamId());
            eq(Duration.ofSeconds(5415), Duration.between(s.getStartedAt(), s.getDeadline()), "duration");
        });
        test("navigating and answering never changes the deadline", () -> {
            Fixture f = new Fixture();
            MultipleChoiceQuestion q = f.mcq("Q", 0, 1);
            Exam e = f.exam("T", Fixture.minutes(10, true, false), 1, q, f.mcq("Q2", 1, 1));
            ExamSession s = f.s.sessions().startOrResume(f.alice, e.getExamId());
            Instant deadline = s.getDeadline();
            for (int i = 0; i < 5; i++) {
                f.clock.advance(Duration.ofSeconds(30));
                f.s.sessions().recordPosition(f.alice, s.getSessionId(), i % 2);
                f.s.sessions().saveAnswer(f.alice, s.getSessionId(), q.getQuestionId(), List.of("0"));
            }
            eq(deadline, s.getDeadline(), "deadline unchanged");
        });
        test("closing and reopening the app preserves the original deadline and answers", () -> {
            Fixture f = new Fixture();
            MultipleChoiceQuestion q = f.mcq("Q", 2, 1);
            Exam e = f.exam("T", Fixture.minutes(20, true, false), 1, q);
            ExamSession s = f.s.sessions().startOrResume(f.alice, e.getExamId());
            f.s.sessions().saveAnswer(f.alice, s.getSessionId(), q.getQuestionId(), List.of("2"));
            Instant deadline = s.getDeadline();
            f.clock.advance(Duration.ofMinutes(5));
            ServiceRegistry restarted = f.reopen();
            ExamSession resumed = restarted.sessions().startOrResume(restarted.store().findUser(f.alice.getUserId()).get(), e.getExamId());
            eq(s.getSessionId(), resumed.getSessionId(), "same session");
            eq(deadline, resumed.getDeadline(), "deadline preserved");
            eq(List.of("2"), resumed.getAnswer(q.getQuestionId()).getValues(), "autosaved answer restored");
            eq(Duration.ofMinutes(15), resumed.remaining(f.clock.instant()), "time kept running while closed");
        });
        test("answering after the deadline throws ExamTimeExpiredException", () -> {
            Fixture f = new Fixture();
            MultipleChoiceQuestion q = f.mcq("Q", 0, 1);
            Exam e = f.exam("T", Fixture.minutes(5, true, false), 1, q);
            ExamSession s = f.s.sessions().startOrResume(f.alice, e.getExamId());
            f.clock.advance(Duration.ofMinutes(5).plusSeconds(1));
            expectThrows(ExamTimeExpiredException.class, () -> f.s.sessions().saveAnswer(f.alice, s.getSessionId(), q.getQuestionId(), List.of("0")));
        });
        test("auto-submit at expiry submits saved answers, marked AUTOMATIC, timestamped at the deadline", () -> {
            Fixture f = new Fixture();
            MultipleChoiceQuestion q = f.mcq("Q", 0, 1);
            Exam e = f.exam("T", Fixture.minutes(5, true, false), 1, q);
            ExamSession s = f.s.sessions().startOrResume(f.alice, e.getExamId());
            f.s.sessions().saveAnswer(f.alice, s.getSessionId(), q.getQuestionId(), List.of("0"));
            f.clock.advance(Duration.ofMinutes(4));
            check(!f.s.sessions().autoSubmitIfDue(s.getSessionId()), "not yet");
            f.clock.advance(Duration.ofMinutes(2));
            check(f.s.sessions().autoSubmitIfDue(s.getSessionId()), "auto-submitted");
            eq(SessionState.SUBMITTED, s.getState(), "state");
            eq(SubmissionType.AUTO, s.getSubmissionType(), "type");
            eq(s.getDeadline(), s.getSubmittedAt(), "submission time = deadline");
            eq(1.0, f.s.grading().findResultForSession(s.getSessionId()).get().getTotalScore(), "answer graded");
        });
        test("manual submission is marked MANUAL and duplicate submissions are blocked", () -> {
            Fixture f = new Fixture();
            Exam e = f.exam("T", Fixture.minutes(5, true, false), 1, f.mcq("Q", 0, 1));
            ExamSession s = f.s.sessions().startOrResume(f.alice, e.getExamId());
            f.s.sessions().submit(f.alice, s.getSessionId());
            eq(SubmissionType.MANUAL, s.getSubmissionType(), "manual");
            expectThrows(DuplicateSubmissionException.class, () -> f.s.sessions().submit(f.alice, s.getSessionId()));
            check(!f.s.sessions().autoSubmitIfDue(s.getSessionId()), "timer cannot submit again");
            eq(1L, f.s.store().getResults().stream().filter(r -> r.getSessionId().equals(s.getSessionId())).count(), "exactly one result");
        });
        test("submitting late is rejected; with auto-submit off the attempt expires", () -> {
            Fixture f = new Fixture();
            Exam e = f.exam("T", Fixture.minutes(5, false, false), 1, f.mcq("Q", 0, 1));
            ExamSession s = f.s.sessions().startOrResume(f.alice, e.getExamId());
            f.clock.advance(Duration.ofMinutes(6));
            expectThrows(ExamTimeExpiredException.class, () -> f.s.sessions().submit(f.alice, s.getSessionId()));
            eq(SessionState.EXPIRED, s.getState(), "expired");
            check(f.s.grading().findResultForSession(s.getSessionId()).isEmpty(), "no result for an unsubmitted attempt");
            expectThrows(ExamTimeExpiredException.class, () -> f.s.sessions().submit(f.alice, s.getSessionId()));
        });
        test("attempt that expired while the app was closed is auto-submitted at start-up", () -> {
            Fixture f = new Fixture();
            Exam e = f.exam("T", Fixture.minutes(10, true, false), 1, f.mcq("Q", 0, 1));
            ExamSession s = f.s.sessions().startOrResume(f.alice, e.getExamId());
            f.clock.advance(Duration.ofHours(1));
            ServiceRegistry restarted = f.reopen();
            eq(1, restarted.sessions().processExpiredSessions(), "one attempt finished");
            ExamSession reloaded = restarted.sessions().findSession(s.getSessionId()).get();
            eq(SubmissionType.AUTO, reloaded.getSubmissionType(), "auto");
            eq(reloaded.getDeadline(), reloaded.getSubmittedAt(), "stamped at deadline, not at restart");
        });
        test("pause freezes the countdown and resume extends the deadline (only if allowed)", () -> {
            Fixture f = new Fixture();
            Exam noPause = f.exam("NP", Fixture.minutes(10, true, false), 1, f.mcq("Q", 0, 1));
            ExamSession s0 = f.s.sessions().startOrResume(f.bob, noPause.getExamId());
            expectThrows(InvalidExamException.class, () -> f.s.sessions().pause(f.bob, s0.getSessionId()));

            Exam e = f.exam("P", Fixture.minutes(10, true, true), 1, f.mcq("Q", 0, 1));
            ExamSession s = f.s.sessions().startOrResume(f.alice, e.getExamId());
            Instant original = s.getDeadline();
            f.clock.advance(Duration.ofMinutes(3));
            f.s.sessions().pause(f.alice, s.getSessionId());
            f.clock.advance(Duration.ofMinutes(20));
            eq(Duration.ofMinutes(7), s.remaining(f.clock.instant()), "frozen while paused");
            check(!f.s.sessions().autoSubmitIfDue(s.getSessionId()), "no expiry while paused");
            f.s.sessions().resume(f.alice, s.getSessionId());
            eq(original.plus(Duration.ofMinutes(20)), s.getDeadline(), "deadline extended by paused time");
            eq(Duration.ofMinutes(7), s.remaining(f.clock.instant()), "remaining kept");
        });
        test("deadline never goes past the exam closing time", () -> {
            Fixture f = new Fixture();
            MultipleChoiceQuestion q = f.mcq("Q", 0, 1);
            Exam e = f.s.exams().createExam(f.teacher, f.subject.getSubjectId(), "Close", "");
            f.s.exams().addQuestion(f.teacher, e.getExamId(), q.getQuestionId());
            f.s.exams().setTimer(f.teacher, e.getExamId(), Fixture.minutes(60, true, false));
            f.s.exams().setAssignWholeSubject(f.teacher, e.getExamId(), true);
            LocalDateTime closes = LocalDateTime.now(f.clock).plusMinutes(20);
            f.s.exams().setSchedule(f.teacher, e.getExamId(), null, closes);
            f.s.exams().publish(f.teacher, e.getExamId());
            ExamSession s = f.s.sessions().startOrResume(f.alice, e.getExamId());
            eq(Duration.ofMinutes(20), s.remaining(f.clock.instant()), "capped by closing time");
        });
        test("teacher closing the exam early finishes attempts in progress", () -> {
            Fixture f = new Fixture();
            Exam e = f.exam("Early", Fixture.minutes(60, true, false), 1, f.mcq("Q", 0, 1));
            ExamSession s = f.s.sessions().startOrResume(f.alice, e.getExamId());
            f.clock.advance(Duration.ofMinutes(1));
            f.s.exams().close(f.teacher, e.getExamId());
            eq(1, f.s.sessions().processExpiredSessions(), "finished");
            eq(SubmissionType.AUTO, s.getSubmissionType(), "auto-submitted");
        });
        test("console timer prints each 10/5/1 minute warning once and auto-submits", () -> {
            Fixture f = new Fixture();
            Exam e = f.exam("Warn", Fixture.minutes(15, true, false), 1, f.mcq("Q", 0, 1));
            ExamSession s = f.s.sessions().startOrResume(f.alice, e.getExamId());
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            ExamTimer timer = new ExamTimer(f.s.sessions(), s, f.clock, new PrintStream(buf, true));
            f.clock.advance(Duration.ofMinutes(5));
            timer.tick();
            timer.tick();
            f.clock.advance(Duration.ofMinutes(5));
            timer.tick();
            f.clock.advance(Duration.ofMinutes(4));
            timer.tick();
            f.clock.advance(Duration.ofMinutes(1));
            timer.tick();
            String output = buf.toString();
            eq(Set.of(600L, 300L, 60L), timer.getWarningsShown(), "all thresholds");
            eq(1, count(output, "10 minute(s) remaining"), "10-minute warning once");
            eq(1, count(output, "5 minute(s) remaining"), "5-minute warning once");
            eq(1, count(output, "1 minute(s) remaining"), "1-minute warning once");
            check(output.contains("TIME IS UP"), "time-up message");
            eq(SubmissionType.AUTO, s.getSubmissionType(), "auto-submitted by the timer");
        });
    }

    private static int count(String text, String needle) {
        int n = 0;
        int i = 0;
        while ((i = text.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }
}
