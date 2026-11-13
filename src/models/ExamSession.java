package models;

import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One student's attempt at one exam - the authoritative record for the timer.
 * <p>
 * The {@link #getDeadline() deadline} is computed once when the attempt starts and saved to
 * disk. The countdown on screen is derived from it ({@code deadline - now}), so navigating
 * between questions or restarting the application can never reset the time.
 * State changes are made only through ExamSessionService.
 */
public class ExamSession implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String sessionId;
    private final String examId;
    private final String studentId;
    private final int attemptNumber;
    private final Instant startedAt;
    private Instant deadline;
    private Instant pausedAt;
    private Duration totalPaused = Duration.ZERO;
    private final List<String> questionOrder;
    private final Map<String, List<Integer>> choiceOrders;
    private final LinkedHashMap<String, StudentAnswer> answers = new LinkedHashMap<>();
    private volatile SessionState state = SessionState.IN_PROGRESS;
    private Instant submittedAt;
    private Instant expiredAt;
    private SubmissionType submissionType;
    private int currentIndex;

    public ExamSession(String sessionId, String examId, String studentId, int attemptNumber, Instant startedAt,
                       Instant deadline, List<String> questionOrder, Map<String, List<Integer>> choiceOrders) {
        this.sessionId = sessionId;
        this.examId = examId;
        this.studentId = studentId;
        this.attemptNumber = attemptNumber;
        this.startedAt = startedAt;
        this.deadline = deadline;
        this.questionOrder = new ArrayList<>(questionOrder);
        this.choiceOrders = new HashMap<>();
        choiceOrders.forEach((k, v) -> this.choiceOrders.put(k, new ArrayList<>(v)));
    }

    /** Remaining time, or null when the attempt has no deadline. Frozen while paused. */
    public Duration remaining(Instant now) {
        if (deadline == null) {
            return null;
        }
        Instant reference = state == SessionState.PAUSED && pausedAt != null ? pausedAt : now;
        Duration d = Duration.between(reference, deadline);
        return d.isNegative() ? Duration.ZERO : d;
    }

    public boolean isOpen() {
        return state == SessionState.IN_PROGRESS || state == SessionState.PAUSED;
    }

    public StudentAnswer getAnswer(String questionId) {
        return answers.get(questionId);
    }

    public StudentAnswer getOrCreateAnswer(String questionId) {
        return answers.computeIfAbsent(questionId, StudentAnswer::new);
    }

    public Map<String, StudentAnswer> getAnswers() {
        return Collections.unmodifiableMap(answers);
    }

    public int countAnswered() {
        return (int) questionOrder.stream().map(answers::get).filter(a -> a != null && a.isAnswered()).count();
    }

    public int countFlagged() {
        return (int) questionOrder.stream().map(answers::get).filter(a -> a != null && a.isMarkedForReview()).count();
    }

    // ------------------------------------------------------------------ state transitions

    public void markPaused(Instant now) {
        state = SessionState.PAUSED;
        pausedAt = now;
    }

    /** Extends the deadline by the paused time, never beyond {@code cap} (the exam closing time). */
    public void markResumed(Instant now, Instant cap) {
        if (pausedAt != null) {
            Duration paused = Duration.between(pausedAt, now);
            if (paused.isNegative()) {
                paused = Duration.ZERO;
            }
            totalPaused = totalPaused.plus(paused);
            if (deadline != null) {
                deadline = deadline.plus(paused);
            }
        }
        if (cap != null && (deadline == null || deadline.isAfter(cap))) {
            deadline = cap;
        }
        pausedAt = null;
        state = SessionState.IN_PROGRESS;
    }

    public void markSubmitted(Instant at, SubmissionType type) {
        submittedAt = at;
        submissionType = type;
        pausedAt = null;
        state = SessionState.SUBMITTED;
    }

    public void markExpired(Instant at) {
        expiredAt = at;
        pausedAt = null;
        state = SessionState.EXPIRED;
    }

    // ------------------------------------------------------------------ getters

    public String getSessionId() {
        return sessionId;
    }

    public String getExamId() {
        return examId;
    }

    public String getStudentId() {
        return studentId;
    }

    public int getAttemptNumber() {
        return attemptNumber;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getDeadline() {
        return deadline;
    }

    public Instant getPausedAt() {
        return pausedAt;
    }

    public Duration getTotalPaused() {
        return totalPaused;
    }

    public List<String> getQuestionOrder() {
        return Collections.unmodifiableList(questionOrder);
    }

    /** Display order of choices for a question, or null if it has none. */
    public List<Integer> getChoiceOrder(String questionId) {
        List<Integer> order = choiceOrders.get(questionId);
        return order == null ? null : Collections.unmodifiableList(order);
    }

    public SessionState getState() {
        return state;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public Instant getExpiredAt() {
        return expiredAt;
    }

    public SubmissionType getSubmissionType() {
        return submissionType;
    }

    public int getCurrentIndex() {
        return currentIndex;
    }

    public void setCurrentIndex(int currentIndex) {
        this.currentIndex = Math.max(0, currentIndex);
    }
}
