package services;

import data.DataStore;
import exceptions.AccessDeniedException;
import exceptions.DuplicateSubmissionException;
import exceptions.ExamTimeExpiredException;
import exceptions.InvalidExamException;
import exceptions.QuizSystemException;
import models.Exam;
import models.ExamSession;
import models.ExamStatus;
import models.Question;
import models.Result;
import models.SessionState;
import models.Student;
import models.SubmissionType;
import models.User;
import util.TimeFormatter;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Taking an exam: starting/resuming attempts, autosaving answers, pausing, submitting, and the
 * authoritative deadline checks.
 * <p>
 * <b>Timer rules.</b> The deadline is computed once at start
 * ({@code min(start + duration, exam closing time)}) and saved immediately. Every write
 * operation re-checks {@code clock.instant()} against it, so the on-screen countdown is never
 * the authority. Methods are {@code synchronized} because the UI thread and the timer thread
 * can both try to finish the same attempt; only one of them can win.
 */
public class ExamSessionService {
    private final DataStore store;
    private final Clock clock;
    private final ExamService examService;
    private final GradingService grading;
    private final Random random;

    public ExamSessionService(DataStore store, Clock clock, ExamService examService, GradingService grading) {
        this(store, clock, examService, grading, new Random());
    }

    public ExamSessionService(DataStore store, Clock clock, ExamService examService, GradingService grading,
                              Random random) {
        this.store = store;
        this.clock = clock;
        this.examService = examService;
        this.grading = grading;
        this.random = random;
    }

    public Instant now() {
        return clock.instant();
    }

    public synchronized Optional<ExamSession> findOpenSession(String studentId, String examId) {
        return store.getSessions().stream()
                .filter(s -> s.getStudentId().equals(studentId) && s.getExamId().equals(examId) && s.isOpen())
                .findFirst();
    }

    public synchronized int attemptsUsed(String studentId, String examId) {
        return (int) store.getSessions().stream()
                .filter(s -> s.getStudentId().equals(studentId) && s.getExamId().equals(examId)).count();
    }

    /**
     * Resumes the student's open attempt (keeping its original deadline) or starts a new one.
     * Throws if the exam is not assigned, not open, or no attempts remain.
     */
    public synchronized ExamSession startOrResume(User actor, String examId) throws QuizSystemException {
        Student student = AccessControl.requireStudent(actor);
        Exam exam = examService.getExam(examId);
        boolean visible = exam.getStatus() == ExamStatus.PUBLISHED || exam.getStatus() == ExamStatus.COMPLETED;
        if (!visible || !examService.isAssigned(exam, student.getUserId())) {
            throw new AccessDeniedException("This examination is not available to you.");
        }

        Optional<ExamSession> open = findOpenSession(student.getUserId(), examId);
        if (open.isPresent()) {
            ExamSession s = open.get();
            if (checkExpiry(s, exam)) {
                throw new ExamTimeExpiredException(describeEnding(s));
            }
            if (s.getState() == SessionState.PAUSED) {
                s.markResumed(now(), exam.getClosingInstant(clock.getZone()));
            }
            store.saveSessions();
            return s;
        }

        ExamStatus status = exam.getEffectiveStatus(LocalDateTime.now(clock));
        if (status == ExamStatus.PUBLISHED) {
            throw new InvalidExamException("This examination opens on " + TimeFormatter.dateTime(exam.getOpensAt()) + ".");
        }
        if (status != ExamStatus.ACTIVE) {
            throw new InvalidExamException("This examination is closed.");
        }
        int used = attemptsUsed(student.getUserId(), examId);
        if (used >= exam.getMaxAttempts()) {
            throw new InvalidExamException("You have used all " + exam.getMaxAttempts() + " attempt(s) for this examination.");
        }

        Instant start = now();
        Instant deadline = exam.getTimer().isEnabled() ? start.plus(exam.getTimer().getDuration()) : null;
        Instant close = exam.getClosingInstant(clock.getZone());
        if (close != null && (deadline == null || close.isBefore(deadline))) {
            deadline = close;
        }
        List<String> order = exam.getQuestions().stream().map(Question::getQuestionId)
                .collect(Collectors.toCollection(ArrayList::new));
        if (exam.isShuffleQuestions()) {
            Collections.shuffle(order, random);
        }
        Map<String, List<Integer>> choiceOrders = new HashMap<>();
        for (Question q : exam.getQuestions()) {
            int n = q.getShuffleableCount();
            if (n > 0) {
                List<Integer> o = IntStream.range(0, n).boxed().collect(Collectors.toCollection(ArrayList::new));
                if (exam.isShuffleChoices()) {
                    Collections.shuffle(o, random);
                }
                choiceOrders.put(q.getQuestionId(), o);
            }
        }
        ExamSession session = new ExamSession(store.nextId("SES"), examId, student.getUserId(), used + 1, start,
                deadline, order, choiceOrders);
        store.addSession(session);
        store.saveSessions();
        return session;
    }

    /**
     * If the attempt's time is up (deadline or exam closing time), finishes it: auto-submits
     * saved answers when auto-submit is on, otherwise marks it EXPIRED.
     * Returns true when this call finished the attempt.
     */
    synchronized boolean checkExpiry(ExamSession s, Exam exam) throws QuizSystemException {
        if (!s.isOpen()) {
            return false;
        }
        Instant now = now();
        Instant cutoff = s.getState() == SessionState.PAUSED ? null : s.getDeadline();
        Instant close = exam.getClosingInstant(clock.getZone());
        if (close != null && (cutoff == null || close.isBefore(cutoff))) {
            cutoff = close;
        }
        if (cutoff == null || now.isBefore(cutoff)) {
            return false;
        }
        if (exam.getTimer().isAutoSubmit()) {
            finalizeSubmission(s, exam, SubmissionType.AUTO, cutoff);
        } else {
            s.markExpired(cutoff);
            store.saveSessions();
        }
        return true;
    }

    /** Called every second by the on-screen timer. Returns true if the attempt was just finished. */
    public synchronized boolean autoSubmitIfDue(String sessionId) throws QuizSystemException {
        ExamSession s = store.findSession(sessionId).orElse(null);
        if (s == null || !s.isOpen()) {
            return false;
        }
        return checkExpiry(s, examService.getExam(s.getExamId()));
    }

    /** Finishes every open attempt whose time ran out while nobody was logged in. */
    public synchronized int processExpiredSessions() {
        int finished = 0;
        for (ExamSession s : new ArrayList<>(store.getSessions())) {
            if (!s.isOpen()) {
                continue;
            }
            try {
                if (checkExpiry(s, examService.getExam(s.getExamId()))) {
                    finished++;
                }
            } catch (QuizSystemException e) {
                // A broken record must not stop the others from being processed.
            }
        }
        return finished;
    }

    public synchronized void saveAnswer(User actor, String sessionId, String questionId, List<String> values)
            throws QuizSystemException {
        ExamSession s = requireOwnSession(actor, sessionId);
        Exam exam = examService.getExam(s.getExamId());
        requireWritable(s, exam);
        if (exam.findQuestion(questionId) == null) {
            throw new InvalidExamException("That question is not part of this examination.");
        }
        s.getOrCreateAnswer(questionId).setValues(values, now());
        store.saveSessions();
    }

    public synchronized void clearAnswer(User actor, String sessionId, String questionId) throws QuizSystemException {
        saveAnswer(actor, sessionId, questionId, List.of());
    }

    public synchronized void setFlag(User actor, String sessionId, String questionId, boolean flagged)
            throws QuizSystemException {
        ExamSession s = requireOwnSession(actor, sessionId);
        requireWritable(s, examService.getExam(s.getExamId()));
        s.getOrCreateAnswer(questionId).setMarkedForReview(flagged);
        store.saveSessions();
    }

    /** Saves the current question position (autosave on navigation). Never touches the deadline. */
    public synchronized void recordPosition(User actor, String sessionId, int index) throws QuizSystemException {
        ExamSession s = requireOwnSession(actor, sessionId);
        if (s.isOpen() && s.getCurrentIndex() != index) {
            s.setCurrentIndex(index);
            store.saveSessions();
        }
    }

    public synchronized void pause(User actor, String sessionId) throws QuizSystemException {
        ExamSession s = requireOwnSession(actor, sessionId);
        Exam exam = examService.getExam(s.getExamId());
        requireWritable(s, exam);
        if (!exam.getTimer().isAllowPause()) {
            throw new InvalidExamException("Pausing is not allowed for this examination.");
        }
        s.markPaused(now());
        store.saveSessions();
    }

    public synchronized void resume(User actor, String sessionId) throws QuizSystemException {
        ExamSession s = requireOwnSession(actor, sessionId);
        if (s.getState() != SessionState.PAUSED) {
            return;
        }
        Exam exam = examService.getExam(s.getExamId());
        if (checkExpiry(s, exam)) {
            throw new ExamTimeExpiredException(describeEnding(s));
        }
        s.markResumed(now(), exam.getClosingInstant(clock.getZone()));
        store.saveSessions();
    }

    /** Manual submission. Rejects duplicates and late submissions. */
    public synchronized Result submit(User actor, String sessionId) throws QuizSystemException {
        ExamSession s = requireOwnSession(actor, sessionId);
        Exam exam = examService.getExam(s.getExamId());
        if (s.getState() == SessionState.SUBMITTED) {
            throw new DuplicateSubmissionException("This attempt was already submitted on "
                    + TimeFormatter.stamp(s.getSubmittedAt(), clock.getZone()) + " ("
                    + s.getSubmissionType().getLabel().toLowerCase() + "). Duplicate submissions are not allowed.");
        }
        if (s.getState() == SessionState.EXPIRED || checkExpiry(s, exam)) {
            throw new ExamTimeExpiredException(describeEnding(s));
        }
        return finalizeSubmission(s, exam, SubmissionType.MANUAL, now());
    }

    private Result finalizeSubmission(ExamSession s, Exam exam, SubmissionType type, Instant at)
            throws QuizSystemException {
        s.markSubmitted(at, type);
        store.saveSessions();
        return grading.autoGrade(s, exam);
    }

    private void requireWritable(ExamSession s, Exam exam) throws QuizSystemException {
        if (checkExpiry(s, exam)) {
            throw new ExamTimeExpiredException(describeEnding(s));
        }
        switch (s.getState()) {
            case SUBMITTED -> throw new InvalidExamException("This attempt was already submitted; answers can no longer be changed.");
            case EXPIRED -> throw new ExamTimeExpiredException(describeEnding(s));
            case PAUSED -> throw new InvalidExamException("The exam is paused. Resume it before answering.");
            default -> {
            }
        }
    }

    /** Human-readable explanation of how an attempt ended. */
    public String describeEnding(ExamSession s) {
        String zoneStamp;
        switch (s.getState()) {
            case SUBMITTED:
                zoneStamp = TimeFormatter.stamp(s.getSubmittedAt(), clock.getZone());
                return s.getSubmissionType() == SubmissionType.AUTO
                        ? "Time expired. Your saved answers were submitted automatically at " + zoneStamp + "."
                        : "This attempt was submitted at " + zoneStamp + ".";
            case EXPIRED:
                zoneStamp = TimeFormatter.stamp(s.getExpiredAt(), clock.getZone());
                return "Time expired at " + zoneStamp + ". Automatic submission is disabled for this examination, "
                        + "so the attempt was closed without a submission.";
            default:
                return "The attempt is still open.";
        }
    }

    public ExamSession requireOwnSession(User actor, String sessionId) throws QuizSystemException {
        Student student = AccessControl.requireStudent(actor);
        ExamSession s = store.findSession(sessionId).orElseThrow(() -> new InvalidExamException("Exam session not found."));
        if (!s.getStudentId().equals(student.getUserId())) {
            throw new AccessDeniedException("This exam session belongs to another student.");
        }
        return s;
    }

    /** The exam's questions in the order this student sees them. */
    public List<Question> questionsInOrder(ExamSession s, Exam exam) {
        List<Question> list = new ArrayList<>();
        for (String id : s.getQuestionOrder()) {
            Question q = exam.findQuestion(id);
            if (q != null) {
                list.add(q);
            }
        }
        return list;
    }

    /** A student's own attempts, newest first. */
    public List<ExamSession> sessionsForStudent(User actor) throws AccessDeniedException {
        Student student = AccessControl.requireStudent(actor);
        return sessionsOfStudent(student.getUserId());
    }

    List<ExamSession> sessionsOfStudent(String studentId) {
        return store.getSessions().stream().filter(s -> s.getStudentId().equals(studentId))
                .sorted(Comparator.comparing(ExamSession::getStartedAt).reversed()).collect(Collectors.toList());
    }

    /** All attempts at an exam (owner teacher or admin), finishing any that have run out of time first. */
    public synchronized List<ExamSession> sessionsForExam(User actor, String examId) throws QuizSystemException {
        examService.requireViewable(actor, examId);
        processExpiredSessions();
        return store.getSessions().stream().filter(s -> s.getExamId().equals(examId))
                .sorted(Comparator.comparing(ExamSession::getStudentId).thenComparing(ExamSession::getAttemptNumber))
                .collect(Collectors.toList());
    }

    public Optional<ExamSession> findSession(String sessionId) {
        return store.findSession(sessionId);
    }
}
