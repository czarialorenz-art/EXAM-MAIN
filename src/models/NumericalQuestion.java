package models;

import exceptions.InvalidQuestionException;
import exceptions.ValidationException;
import util.TextUtil;

import java.util.List;

/** Numeric answer, correct when within the configured tolerance. */
public class NumericalQuestion extends Question {
    private static final long serialVersionUID = 1L;
    private static final double EPSILON = 1e-9;

    private double correctValue;
    private double tolerance;

    public NumericalQuestion(String questionId, String teacherId, String subjectId, String text, double points,
                             String category, Difficulty difficulty, String explanation, double correctValue,
                             double tolerance) throws InvalidQuestionException {
        super(questionId, teacherId, subjectId, text, points, category, difficulty, explanation);
        this.correctValue = correctValue;
        this.tolerance = tolerance;
        validateSpecific();
    }

    public double getCorrectValue() {
        return correctValue;
    }

    public double getTolerance() {
        return tolerance;
    }

    @Override
    public QuestionType getType() {
        return QuestionType.NUMERICAL;
    }

    @Override
    protected String renderOptions(List<Integer> displayOrder) {
        return "   (Numeric answer)";
    }

    @Override
    public String getAnswerHint() {
        return "Type a number (e.g. 42 or 3.14). Use '.' as the decimal point.";
    }

    @Override
    public List<String> parseAnswer(String raw, List<Integer> displayOrder) throws ValidationException {
        String t = requireAnswerText(raw);
        try {
            double v = Double.parseDouble(t);
            if (!Double.isFinite(v)) {
                throw new NumberFormatException();
            }
            return List.of(String.valueOf(v));
        } catch (NumberFormatException e) {
            throw new ValidationException("'" + t + "' is not a valid number.");
        }
    }

    @Override
    public String formatAnswer(List<String> values, List<Integer> displayOrder) {
        if (values.isEmpty()) {
            return "(no answer)";
        }
        try {
            return TextUtil.num(Double.parseDouble(values.get(0)));
        } catch (NumberFormatException e) {
            return values.get(0);
        }
    }

    @Override
    protected GradeOutcome grade(List<String> values) {
        double given = Double.parseDouble(values.get(0));
        boolean correct = Math.abs(given - correctValue) <= tolerance + EPSILON;
        return GradeOutcome.of(correct ? getPoints() : 0, getPoints());
    }

    @Override
    public String describeAnswerKey() {
        return TextUtil.num(correctValue) + (tolerance > 0 ? " (+/- " + TextUtil.num(tolerance) + ")" : "");
    }

    @Override
    protected void validateSpecific() throws InvalidQuestionException {
        if (!Double.isFinite(correctValue)) {
            throw new InvalidQuestionException("The correct value must be a valid number.");
        }
        if (!Double.isFinite(tolerance) || tolerance < 0) {
            throw new InvalidQuestionException("Tolerance must be zero or a positive number.");
        }
    }
}
