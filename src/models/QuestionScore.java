package models;

import exceptions.ValidationException;
import util.TextUtil;

import java.io.Serializable;

/** Score of one question inside a {@link Result}. */
public class QuestionScore implements Serializable {
    private static final long serialVersionUID = 1L;
    public static final String SYSTEM = "SYSTEM";

    private final String questionId;
    private final double maxPoints;
    private double earned;
    private boolean pendingManual;
    private String feedback = "";
    private String gradedBy;

    public QuestionScore(String questionId, GradeOutcome outcome) {
        this.questionId = questionId;
        this.maxPoints = outcome.getMax();
        this.earned = outcome.getEarned();
        this.pendingManual = outcome.isPendingManual();
        this.gradedBy = pendingManual ? null : SYSTEM;
    }

    /** Teacher grading; points must lie within [0, max]. */
    public void applyManualGrade(double points, String feedback, String teacherId) throws ValidationException {
        if (Double.isNaN(points) || points < 0 || points > maxPoints) {
            throw new ValidationException("Points must be between 0 and " + TextUtil.num(maxPoints) + ".");
        }
        this.earned = points;
        this.feedback = feedback == null ? "" : feedback.trim();
        this.pendingManual = false;
        this.gradedBy = teacherId;
    }

    public String getQuestionId() {
        return questionId;
    }

    public double getMaxPoints() {
        return maxPoints;
    }

    public double getEarned() {
        return earned;
    }

    public boolean isPendingManual() {
        return pendingManual;
    }

    public String getFeedback() {
        return feedback;
    }

    public String getGradedBy() {
        return gradedBy;
    }
}
