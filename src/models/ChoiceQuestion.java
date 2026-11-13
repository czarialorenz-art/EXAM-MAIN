package models;

import exceptions.InvalidQuestionException;
import exceptions.ValidationException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Abstract parent for question types answered by picking lettered choices (2-6 choices). */
public abstract class ChoiceQuestion extends Question {
    private static final long serialVersionUID = 1L;
    public static final int MIN_CHOICES = 2;
    public static final int MAX_CHOICES = 6;

    private List<String> choices = new ArrayList<>();

    protected ChoiceQuestion(String questionId, String teacherId, String subjectId, String text, double points,
                             String category, Difficulty difficulty, String explanation, List<String> choices)
            throws InvalidQuestionException {
        super(questionId, teacherId, subjectId, text, points, category, difficulty, explanation);
        setChoices(choices);
    }

    public List<String> getChoices() {
        return Collections.unmodifiableList(choices);
    }

    public final void setChoices(List<String> newChoices) throws InvalidQuestionException {
        List<String> cleaned = cleanList(newChoices);
        if (cleaned.size() < MIN_CHOICES || cleaned.size() > MAX_CHOICES) {
            throw new InvalidQuestionException("A choice question needs between " + MIN_CHOICES + " and "
                    + MAX_CHOICES + " non-empty choices.");
        }
        Set<String> seen = new HashSet<>();
        for (String c : cleaned) {
            if (!seen.add(normalize(c))) {
                throw new InvalidQuestionException("Duplicate choice: '" + c + "'.");
            }
        }
        this.choices = new ArrayList<>(cleaned);
    }

    @Override
    public int getShuffleableCount() {
        return choices.size();
    }

    @Override
    protected String renderOptions(List<Integer> displayOrder) {
        List<Integer> order = safeOrder(displayOrder, choices.size());
        StringBuilder sb = new StringBuilder();
        for (int pos = 0; pos < order.size(); pos++) {
            if (pos > 0) {
                sb.append(System.lineSeparator());
            }
            sb.append("   ").append(letter(pos)).append(". ").append(choices.get(order.get(pos)));
        }
        return sb.toString();
    }

    /** Displayed letter -> original choice index. */
    protected int toOriginal(String token, List<Integer> displayOrder) throws ValidationException {
        int pos = parseLetter(token, choices.size());
        return safeOrder(displayOrder, choices.size()).get(pos);
    }

    /** Original choice index -> "B. text" as the student saw it. */
    protected String describeChoice(String storedIndex, List<Integer> displayOrder) {
        try {
            int original = Integer.parseInt(storedIndex);
            int pos = safeOrder(displayOrder, choices.size()).indexOf(original);
            return (pos < 0 ? "?" : letter(pos)) + ". " + choices.get(original);
        } catch (NumberFormatException | IndexOutOfBoundsException e) {
            return "(unreadable answer)";
        }
    }

    /** Answer-key label using the original (unshuffled) lettering. */
    protected String keyLabel(int original) {
        return letter(original) + ". " + choices.get(original);
    }

    protected boolean inRange(int index) {
        return index >= 0 && index < choices.size();
    }
}
