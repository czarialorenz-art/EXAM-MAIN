package models;

import exceptions.InvalidExamException;
import util.TextUtil;

import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A quiz or examination.
 * <p>
 * While DRAFT, the exam references bank questions by ID ({@link #getDraftQuestionIds()}).
 * On publish, deep copies of those questions are stored in {@link #getQuestions()} so later
 * edits to the question bank never change an exam students are taking or have taken.
 */
public class Exam implements Serializable {
    private static final long serialVersionUID = 1L;
    public static final int MAX_ATTEMPTS_LIMIT = 10;

    private final String examId;
    private final String teacherId;
    private final String subjectId;
    private final LocalDateTime createdAt;
    private String title;
    private String instructions;
    private double passingPercentage = 60.0;
    private TimerSettings timer = TimerSettings.defaults();
    private LocalDateTime opensAt;
    private LocalDateTime closesAt;
    private int maxAttempts = 1;
    private boolean shuffleQuestions;
    private boolean shuffleChoices;
    private boolean autoReleaseResults;
    private boolean assignWholeSubject;
    private final List<String> draftQuestionIds = new ArrayList<>();
    private final List<Question> questions = new ArrayList<>();
    private final Set<String> assignedStudentIds = new LinkedHashSet<>();
    private final Set<String> assignedClassIds = new LinkedHashSet<>();
    private ExamStatus status = ExamStatus.DRAFT;
    private LocalDateTime publishedAt;

    public Exam(String examId, String teacherId, String subjectId, String title, String instructions,
                LocalDateTime createdAt) throws InvalidExamException {
        this.examId = examId;
        this.teacherId = teacherId;
        this.subjectId = subjectId;
        this.createdAt = createdAt;
        setTitle(title);
        setInstructions(instructions);
    }

    // ------------------------------------------------------------------ static validation rules

    public static String checkTitle(String title) throws InvalidExamException {
        if (title == null || title.isBlank()) {
            throw new InvalidExamException("Exam title is required.");
        }
        if (title.trim().length() > 100) {
            throw new InvalidExamException("Exam title must be at most 100 characters.");
        }
        return title.trim();
    }

    public static double checkPassing(double pct) throws InvalidExamException {
        if (Double.isNaN(pct) || pct < 0 || pct > 100) {
            throw new InvalidExamException("Passing score must be between 0 and 100 percent.");
        }
        return pct;
    }

    public static int checkAttempts(int attempts) throws InvalidExamException {
        if (attempts < 1 || attempts > MAX_ATTEMPTS_LIMIT) {
            throw new InvalidExamException("Maximum attempts must be between 1 and " + MAX_ATTEMPTS_LIMIT + ".");
        }
        return attempts;
    }

    public static void checkSchedule(LocalDateTime opens, LocalDateTime closes) throws InvalidExamException {
        if (opens != null && closes != null && !closes.isAfter(opens)) {
            throw new InvalidExamException("The closing date/time must be after the opening date/time.");
        }
    }

    // ------------------------------------------------------------------ status

    /**
     * The status students experience right now. A PUBLISHED exam is ACTIVE between its
     * opening and closing times and COMPLETED after closing, without any background job.
     */
    public ExamStatus getEffectiveStatus(LocalDateTime now) {
        if (status != ExamStatus.PUBLISHED) {
            return status;
        }
        if (closesAt != null && !now.isBefore(closesAt)) {
            return ExamStatus.COMPLETED;
        }
        if (opensAt != null && now.isBefore(opensAt)) {
            return ExamStatus.PUBLISHED;
        }
        return ExamStatus.ACTIVE;
    }

    public boolean isDraft() {
        return status == ExamStatus.DRAFT;
    }

    public void markPublished(List<Question> snapshot, LocalDateTime now) {
        questions.clear();
        questions.addAll(snapshot);
        status = ExamStatus.PUBLISHED;
        publishedAt = now;
    }

    public void markUnpublished() {
        questions.clear();
        status = ExamStatus.DRAFT;
        publishedAt = null;
    }

    /** Closes the exam now; open attempts are finalised by the session service. */
    public void markClosed(LocalDateTime now) {
        if (closesAt == null || closesAt.isAfter(now)) {
            closesAt = now;
        }
        status = ExamStatus.COMPLETED;
    }

    public void markArchived(LocalDateTime now) {
        if (status == ExamStatus.PUBLISHED && (closesAt == null || closesAt.isAfter(now))) {
            closesAt = now;
        }
        status = ExamStatus.ARCHIVED;
    }

    // ------------------------------------------------------------------ questions

    public boolean addDraftQuestion(String questionId) {
        if (draftQuestionIds.contains(questionId)) {
            return false;
        }
        return draftQuestionIds.add(questionId);
    }

    public boolean removeDraftQuestion(String questionId) {
        return draftQuestionIds.remove(questionId);
    }

    public List<String> getDraftQuestionIds() {
        return Collections.unmodifiableList(draftQuestionIds);
    }

    /** Snapshot questions (empty while DRAFT). */
    public List<Question> getQuestions() {
        return Collections.unmodifiableList(questions);
    }

    public Question findQuestion(String questionId) {
        for (Question q : questions) {
            if (q.getQuestionId().equals(questionId)) {
                return q;
            }
        }
        return null;
    }

    public double getTotalPoints() {
        return questions.stream().mapToDouble(Question::getPoints).sum();
    }

    // ------------------------------------------------------------------ assignment

    public boolean assignStudent(String studentId) {
        return assignedStudentIds.add(studentId);
    }

    public boolean unassignStudent(String studentId) {
        return assignedStudentIds.remove(studentId);
    }

    public boolean assignClass(String classId) {
        return assignedClassIds.add(classId);
    }

    public boolean unassignClass(String classId) {
        return assignedClassIds.remove(classId);
    }

    public Set<String> getAssignedStudentIds() {
        return Collections.unmodifiableSet(assignedStudentIds);
    }

    public Set<String> getAssignedClassIds() {
        return Collections.unmodifiableSet(assignedClassIds);
    }

    // ------------------------------------------------------------------ getters / setters

    public String getExamId() {
        return examId;
    }

    public String getTeacherId() {
        return teacherId;
    }

    public String getSubjectId() {
        return subjectId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) throws InvalidExamException {
        this.title = checkTitle(title);
    }

    public String getInstructions() {
        return instructions;
    }

    public void setInstructions(String instructions) throws InvalidExamException {
        String text = instructions == null ? "" : instructions.trim();
        if (text.length() > 3000) {
            throw new InvalidExamException("Instructions must be at most 3000 characters.");
        }
        this.instructions = text;
    }

    public double getPassingPercentage() {
        return passingPercentage;
    }

    public void setPassingPercentage(double passingPercentage) throws InvalidExamException {
        this.passingPercentage = checkPassing(passingPercentage);
    }

    public TimerSettings getTimer() {
        return timer;
    }

    public void setTimer(TimerSettings timer) throws InvalidExamException {
        if (timer == null) {
            throw new InvalidExamException("Timer settings are required.");
        }
        timer.validate();
        this.timer = timer;
    }

    public LocalDateTime getOpensAt() {
        return opensAt;
    }

    public LocalDateTime getClosesAt() {
        return closesAt;
    }

    public void setSchedule(LocalDateTime opensAt, LocalDateTime closesAt) throws InvalidExamException {
        checkSchedule(opensAt, closesAt);
        this.opensAt = opensAt;
        this.closesAt = closesAt;
    }

    /** Closing time as an Instant in the given zone, or null when there is no closing time. */
    public Instant getClosingInstant(ZoneId zone) {
        return closesAt == null ? null : closesAt.atZone(zone).toInstant();
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) throws InvalidExamException {
        this.maxAttempts = checkAttempts(maxAttempts);
    }

    public boolean isShuffleQuestions() {
        return shuffleQuestions;
    }

    public void setShuffleQuestions(boolean shuffleQuestions) {
        this.shuffleQuestions = shuffleQuestions;
    }

    public boolean isShuffleChoices() {
        return shuffleChoices;
    }

    public void setShuffleChoices(boolean shuffleChoices) {
        this.shuffleChoices = shuffleChoices;
    }

    public boolean isAutoReleaseResults() {
        return autoReleaseResults;
    }

    public void setAutoReleaseResults(boolean autoReleaseResults) {
        this.autoReleaseResults = autoReleaseResults;
    }

    public boolean isAssignWholeSubject() {
        return assignWholeSubject;
    }

    public void setAssignWholeSubject(boolean assignWholeSubject) {
        this.assignWholeSubject = assignWholeSubject;
    }

    public ExamStatus getStatus() {
        return status;
    }

    public LocalDateTime getPublishedAt() {
        return publishedAt;
    }

    @Override
    public String toString() {
        return examId + " " + TextUtil.truncate(title, 50) + " [" + status.getLabel() + "]";
    }
}
