package models;

import exceptions.InvalidQuestionException;
import exceptions.ValidationException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * List several items. Graded automatically (each expected item earns an equal share, in any
 * order or in exact order) unless the teacher chooses manual grading.
 */
public class EnumerationQuestion extends Question {
    private static final long serialVersionUID = 1L;

    private List<String> expectedItems = new ArrayList<>();
    private boolean orderMatters;
    private boolean manualGrading;

    public EnumerationQuestion(String questionId, String teacherId, String subjectId, String text, double points,
                               String category, Difficulty difficulty, String explanation, List<String> expectedItems,
                               boolean orderMatters, boolean manualGrading) throws InvalidQuestionException {
        super(questionId, teacherId, subjectId, text, points, category, difficulty, explanation);
        this.expectedItems = cleanList(expectedItems);
        this.orderMatters = orderMatters;
        this.manualGrading = manualGrading;
        validateSpecific();
    }

    public List<String> getExpectedItems() {
        return Collections.unmodifiableList(expectedItems);
    }

    public boolean isOrderMatters() {
        return orderMatters;
    }

    public boolean isManualGrading() {
        return manualGrading;
    }

    @Override
    public QuestionType getType() {
        return QuestionType.ENUMERATION;
    }

    @Override
    public boolean requiresManualGrading() {
        return manualGrading;
    }

    @Override
    protected String renderOptions(List<Integer> displayOrder) {
        return "   (List " + expectedItems.size() + " item(s)" + (orderMatters ? ", in the correct order)" : ")");
    }

    @Override
    public String getAnswerHint() {
        return "Type the items separated by ';' (e.g. first item; second item).";
    }

    @Override
    public List<String> parseAnswer(String raw, List<Integer> displayOrder) throws ValidationException {
        List<String> items = splitList(requireAnswerText(raw));
        if (items.isEmpty()) {
            throw new ValidationException("Answer cannot be empty.");
        }
        return items;
    }

    @Override
    public String formatAnswer(List<String> values, List<Integer> displayOrder) {
        return values.isEmpty() ? "(no answer)" : String.join("; ", values);
    }

    @Override
    protected GradeOutcome grade(List<String> values) {
        if (manualGrading) {
            return GradeOutcome.pending(getPoints());
        }
        int n = expectedItems.size();
        // Only the first n items count, so listing extra guesses cannot raise the score.
        List<String> given = new ArrayList<>();
        for (int i = 0; i < values.size() && i < n; i++) {
            given.add(normalize(values.get(i)));
        }
        int correct = 0;
        if (orderMatters) {
            for (int i = 0; i < given.size(); i++) {
                if (normalize(expectedItems.get(i)).equals(given.get(i))) {
                    correct++;
                }
            }
        } else {
            List<String> remaining = new ArrayList<>();
            expectedItems.forEach(e -> remaining.add(normalize(e)));
            for (String g : given) {
                if (remaining.remove(g)) {
                    correct++;
                }
            }
        }
        return GradeOutcome.of(getPoints() * correct / n, getPoints());
    }

    @Override
    public String describeAnswerKey() {
        return String.join("; ", expectedItems) + (orderMatters ? " (in order)" : " (any order)")
                + (manualGrading ? " - graded manually" : "");
    }

    @Override
    protected void validateSpecific() throws InvalidQuestionException {
        if (expectedItems.isEmpty()) {
            throw new InvalidQuestionException("Provide at least one expected item.");
        }
    }
}
