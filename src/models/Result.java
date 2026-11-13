package models;

import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Graded outcome of one submitted attempt. A result becomes FINAL only when no question is
 * waiting for manual grading; only FINAL results can be released to the student.
 */
public class Result implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String resultId;
    private final String sessionId;
    private final String examId;
    private final String studentId;
    private final int attemptNumber;
    private final LinkedHashMap<String, QuestionScore> scores = new LinkedHashMap<>();
    private double totalScore;
    private double maxScore;
    private double percentage;
    private boolean passed;
    private ResultStatus status = ResultStatus.PENDING_MANUAL;
    private boolean released;
    private String overallFeedback = "";
    private Instant gradedAt;
    private Instant releasedAt;

    public Result(String resultId, ExamSession session) {
        this.resultId = resultId;
        this.sessionId = session.getSessionId();
        this.examId = session.getExamId();
        this.studentId = session.getStudentId();
        this.attemptNumber = session.getAttemptNumber();
    }

    public void addScore(QuestionScore score) {
        scores.put(score.getQuestionId(), score);
    }

    /** Recomputes totals, percentage, pass/fail and status from the per-question scores. */
    public void recalculate(double passingPercentage, Instant now) {
        totalScore = scores.values().stream().mapToDouble(QuestionScore::getEarned).sum();
        maxScore = scores.values().stream().mapToDouble(QuestionScore::getMaxPoints).sum();
        percentage = maxScore > 0 ? totalScore / maxScore * 100.0 : 0;
        boolean pending = scores.values().stream().anyMatch(QuestionScore::isPendingManual);
        status = pending ? ResultStatus.PENDING_MANUAL : ResultStatus.FINAL;
        passed = !pending && percentage + 1e-9 >= passingPercentage;
        gradedAt = now;
    }

    public List<String> getPendingQuestionIds() {
        List<String> ids = new ArrayList<>();
        scores.values().stream().filter(QuestionScore::isPendingManual).forEach(s -> ids.add(s.getQuestionId()));
        return ids;
    }

    public void release(Instant now) {
        if (status != ResultStatus.FINAL) {
            throw new IllegalStateException("A result cannot be released while manual grading is incomplete.");
        }
        released = true;
        releasedAt = now;
    }

    public void withdrawRelease() {
        released = false;
        releasedAt = null;
    }

    public boolean isFinal() {
        return status == ResultStatus.FINAL;
    }

    public String getResultId() {
        return resultId;
    }

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

    public Map<String, QuestionScore> getScores() {
        return Collections.unmodifiableMap(scores);
    }

    public QuestionScore getScore(String questionId) {
        return scores.get(questionId);
    }

    public double getTotalScore() {
        return totalScore;
    }

    public double getMaxScore() {
        return maxScore;
    }

    public double getPercentage() {
        return percentage;
    }

    public boolean isPassed() {
        return passed;
    }

    public ResultStatus getStatus() {
        return status;
    }

    public boolean isReleased() {
        return released;
    }

    public String getOverallFeedback() {
        return overallFeedback;
    }

    public void setOverallFeedback(String overallFeedback) {
        this.overallFeedback = overallFeedback == null ? "" : overallFeedback.trim();
    }

    public Instant getGradedAt() {
        return gradedAt;
    }

    public Instant getReleasedAt() {
        return releasedAt;
    }
}
