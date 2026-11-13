package models;

import java.io.Serializable;

/** Immutable result of grading one answer: points earned, maximum, and whether a teacher must grade it. */
public final class GradeOutcome implements Serializable {
    private static final long serialVersionUID = 1L;

    private final double earned;
    private final double max;
    private final boolean pendingManual;

    private GradeOutcome(double earned, double max, boolean pendingManual) {
        this.earned = earned;
        this.max = max;
        this.pendingManual = pendingManual;
    }

    /** Points are clamped into [0, max]. */
    public static GradeOutcome of(double earned, double max) {
        double clamped = Math.max(0, Math.min(max, earned));
        return new GradeOutcome(clamped, max, false);
    }

    public static GradeOutcome pending(double max) {
        return new GradeOutcome(0, max, true);
    }

    public double getEarned() {
        return earned;
    }

    public double getMax() {
        return max;
    }

    public boolean isPendingManual() {
        return pendingManual;
    }
}
