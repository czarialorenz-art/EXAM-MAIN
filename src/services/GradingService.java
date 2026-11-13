package services;

import data.DataStore;
import exceptions.DuplicateSubmissionException;
import exceptions.InvalidExamException;
import exceptions.QuizSystemException;
import exceptions.ValidationException;
import models.Exam;
import models.ExamSession;
import models.GradeOutcome;
import models.Question;
import models.QuestionScore;
import models.Result;
import models.ResultStatus;
import models.Role;
import models.SessionState;
import models.User;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/** Automatic grading on submission, manual grading by teachers, and result release. */
public class GradingService {
    public static final Comparator<Result> BY_SCORE_DESC = Comparator.comparingDouble(Result::getPercentage).reversed()
            .thenComparing(Result::getStudentId).thenComparing(Result::getAttemptNumber);

    private final DataStore store;
    private final Clock clock;
    private final ExamService examService;

    public GradingService(DataStore store, Clock clock, ExamService examService) {
        this.store = store;
        this.clock = clock;
        this.examService = examService;
    }

    /**
     * Grades every question polymorphically via {@link Question#evaluate}. Subjective answers
     * stay pending, which keeps the result in PENDING_MANUAL until a teacher grades them.
     */
    public synchronized Result autoGrade(ExamSession session, Exam exam) throws QuizSystemException {
        if (session.getState() != SessionState.SUBMITTED) {
            throw new InvalidExamException("Only submitted attempts can be graded.");
        }
        if (store.findResultBySession(session.getSessionId()).isPresent()) {
            throw new DuplicateSubmissionException("A result already exists for this attempt; it cannot be graded twice.");
        }
        Result result = new Result(store.nextId("RES"), session);
        for (Question q : exam.getQuestions()) {
            GradeOutcome outcome = q.evaluate(session.getAnswer(q.getQuestionId()));
            result.addScore(new QuestionScore(q.getQuestionId(), outcome));
        }
        result.recalculate(exam.getPassingPercentage(), clock.instant());
        if (result.isFinal() && exam.isAutoReleaseResults()) {
            result.release(clock.instant());
        }
        store.addResult(result);
        store.saveResults();
        return result;
    }

    /** Teacher grades (or regrades) one question of one result. */
    public synchronized Result gradeQuestion(User actor, String resultId, String questionId, double points,
                                             String feedback) throws QuizSystemException {
        Result r = getResult(resultId);
        Exam exam = examService.requireOwned(actor, r.getExamId());
        if (r.isReleased()) {
            throw new ValidationException("This result has already been released to the student. "
                    + "Withdraw the release before changing scores.");
        }
        QuestionScore score = r.getScore(questionId);
        if (score == null) {
            throw new InvalidExamException("That question is not part of this result.");
        }
        score.applyManualGrade(points, feedback, actor.getUserId());
        r.recalculate(exam.getPassingPercentage(), clock.instant());
        if (r.isFinal() && exam.isAutoReleaseResults()) {
            r.release(clock.instant());
        }
        store.saveResults();
        return r;
    }

    public synchronized void setOverallFeedback(User actor, String resultId, String feedback) throws QuizSystemException {
        Result r = getResult(resultId);
        examService.requireOwned(actor, r.getExamId());
        if (feedback != null && feedback.length() > 2000) {
            throw new ValidationException("Feedback must be at most 2000 characters.");
        }
        r.setOverallFeedback(feedback);
        store.saveResults();
    }

    /** Releases every FINAL result of an exam. Returns {released, stillPending}. */
    public synchronized int[] releaseResults(User actor, String examId) throws QuizSystemException {
        examService.requireOwned(actor, examId);
        int released = 0;
        int pending = 0;
        for (Result r : store.getResults()) {
            if (!r.getExamId().equals(examId) || r.isReleased()) {
                continue;
            }
            if (r.isFinal()) {
                r.release(clock.instant());
                released++;
            } else {
                pending++;
            }
        }
        store.saveResults();
        return new int[]{released, pending};
    }

    public synchronized void releaseResult(User actor, String resultId) throws QuizSystemException {
        Result r = getResult(resultId);
        examService.requireOwned(actor, r.getExamId());
        if (!r.isFinal()) {
            throw new InvalidExamException("This result still has " + r.getPendingQuestionIds().size()
                    + " answer(s) awaiting manual grading, so it cannot be marked final or released yet.");
        }
        r.release(clock.instant());
        store.saveResults();
    }

    public synchronized void withdrawRelease(User actor, String resultId) throws QuizSystemException {
        Result r = getResult(resultId);
        examService.requireOwned(actor, r.getExamId());
        r.withdrawRelease();
        store.saveResults();
    }

    /** Results with answers awaiting manual grading, for the teacher's exams. */
    public List<Result> pendingResults(User actor) throws QuizSystemException {
        AccessControl.requireTeacher(actor);
        return store.getResults().stream().filter(r -> r.getStatus() == ResultStatus.PENDING_MANUAL)
                .filter(r -> store.findExam(r.getExamId()).map(e -> e.getTeacherId().equals(actor.getUserId())).orElse(false))
                .collect(Collectors.toList());
    }

    /** All results of an exam sorted from highest to lowest score (teacher owner or admin). */
    public List<Result> resultsForExam(User actor, String examId) throws QuizSystemException {
        examService.requireViewable(actor, examId);
        return store.getResults().stream().filter(r -> r.getExamId().equals(examId)).sorted(BY_SCORE_DESC)
                .collect(Collectors.toList());
    }

    public Result getResult(String resultId) throws InvalidExamException {
        return store.findResult(resultId).orElseThrow(() -> new InvalidExamException("Result not found."));
    }

    public Optional<Result> findResultForSession(String sessionId) {
        return store.findResultBySession(sessionId);
    }

    /** A student may see a score only for their own released, final result. */
    public static boolean isVisibleToStudent(Result r, User student) {
        return student != null && student.getRole() == Role.STUDENT && r.getStudentId().equals(student.getUserId())
                && r.isReleased() && r.isFinal();
    }
}
