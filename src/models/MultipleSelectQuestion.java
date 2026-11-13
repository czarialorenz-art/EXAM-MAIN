package models;

import exceptions.InvalidQuestionException;
import exceptions.ValidationException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeSet;

/**
 * One or more correct choices. With partial credit the score is
 * (correct picks - wrong picks) / number of correct answers, never below zero.
 */
public class MultipleSelectQuestion extends ChoiceQuestion {
    private static final long serialVersionUID = 1L;

    private final TreeSet<Integer> correctIndexes = new TreeSet<>();
    private boolean partialCredit;

    public MultipleSelectQuestion(String questionId, String teacherId, String subjectId, String text, double points,
                                  String category, Difficulty difficulty, String explanation, List<String> choices,
                                  Collection<Integer> correct, boolean partialCredit) throws InvalidQuestionException {
        super(questionId, teacherId, subjectId, text, points, category, difficulty, explanation, choices);
        if (correct != null) {
            correctIndexes.addAll(correct);
        }
        this.partialCredit = partialCredit;
        validateSpecific();
    }

    public Set<Integer> getCorrectIndexes() {
        return new TreeSet<>(correctIndexes);
    }

    public boolean isPartialCredit() {
        return partialCredit;
    }

    @Override
    public QuestionType getType() {
        return QuestionType.MULTIPLE_SELECT;
    }

    @Override
    protected String renderOptions(List<Integer> displayOrder) {
        return super.renderOptions(displayOrder) + System.lineSeparator() + "   (Select ALL correct answers)";
    }

    @Override
    public String getAnswerHint() {
        return "Type every correct letter separated by commas (e.g. A,C).";
    }

    @Override
    public List<String> parseAnswer(String raw, List<Integer> displayOrder) throws ValidationException {
        String t = requireAnswerText(raw);
        String[] tokens = t.matches("[A-Za-z]+") ? t.split("") : t.split("[,\\s]+");
        TreeSet<Integer> picked = new TreeSet<>();
        for (String token : tokens) {
            if (!token.isBlank()) {
                picked.add(toOriginal(token, displayOrder));
            }
        }
        if (picked.isEmpty()) {
            throw new ValidationException("Select at least one choice.");
        }
        List<String> values = new ArrayList<>();
        picked.forEach(i -> values.add(String.valueOf(i)));
        return values;
    }

    @Override
    public String formatAnswer(List<String> values, List<Integer> displayOrder) {
        if (values.isEmpty()) {
            return "(no answer)";
        }
        StringJoiner j = new StringJoiner("; ");
        values.forEach(v -> j.add(describeChoice(v, displayOrder)));
        return j.toString();
    }

    @Override
    protected GradeOutcome grade(List<String> values) {
        Set<Integer> selected = new TreeSet<>();
        for (String v : values) {
            selected.add(Integer.parseInt(v));
        }
        if (!partialCredit) {
            return GradeOutcome.of(selected.equals(correctIndexes) ? getPoints() : 0, getPoints());
        }
        long right = selected.stream().filter(correctIndexes::contains).count();
        long wrong = selected.size() - right;
        double ratio = Math.max(0, right - wrong) / (double) correctIndexes.size();
        return GradeOutcome.of(ratio * getPoints(), getPoints());
    }

    @Override
    public String describeAnswerKey() {
        StringJoiner j = new StringJoiner("; ");
        correctIndexes.forEach(i -> j.add(keyLabel(i)));
        return j + (partialCredit ? " (partial credit)" : " (all must be correct)");
    }

    @Override
    protected void validateSpecific() throws InvalidQuestionException {
        if (correctIndexes.isEmpty()) {
            throw new InvalidQuestionException("Select at least one correct choice.");
        }
        for (int i : correctIndexes) {
            if (!inRange(i)) {
                throw new InvalidQuestionException("Correct answers must be among the choices.");
            }
        }
    }
}
