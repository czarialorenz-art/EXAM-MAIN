package models;

import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A student's answer to one question. Values are stored in a canonical form produced by
 * {@link Question#parseAnswer} (e.g. original choice indexes), so shuffling never affects grading.
 */
public class StudentAnswer implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String questionId;
    private List<String> values = new ArrayList<>();
    private boolean markedForReview;
    private Instant lastSavedAt;
    private int revision;

    public StudentAnswer(String questionId) {
        this.questionId = questionId;
    }

    /** Convenience factory, mainly for tests. */
    public static StudentAnswer of(String questionId, String... values) {
        StudentAnswer a = new StudentAnswer(questionId);
        a.setValues(List.of(values), null);
        return a;
    }

    public String getQuestionId() {
        return questionId;
    }

    public List<String> getValues() {
        return Collections.unmodifiableList(values);
    }

    public void setValues(List<String> newValues, Instant savedAt) {
        this.values = new ArrayList<>(newValues == null ? List.of() : newValues);
        this.lastSavedAt = savedAt;
        this.revision++;
    }

    public boolean isAnswered() {
        return values.stream().anyMatch(v -> v != null && !v.isBlank());
    }

    public boolean isMarkedForReview() {
        return markedForReview;
    }

    public void setMarkedForReview(boolean markedForReview) {
        this.markedForReview = markedForReview;
    }

    public Instant getLastSavedAt() {
        return lastSavedAt;
    }

    public int getRevision() {
        return revision;
    }
}
