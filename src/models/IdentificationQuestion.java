package models;

import exceptions.InvalidQuestionException;
import exceptions.ValidationException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Short typed answer compared against a list of accepted answers. */
public class IdentificationQuestion extends Question {
    private static final long serialVersionUID = 1L;

    private List<String> acceptedAnswers = new ArrayList<>();
    private boolean caseSensitive;

    public IdentificationQuestion(String questionId, String teacherId, String subjectId, String text, double points,
                                  String category, Difficulty difficulty, String explanation,
                                  List<String> acceptedAnswers, boolean caseSensitive) throws InvalidQuestionException {
        super(questionId, teacherId, subjectId, text, points, category, difficulty, explanation);
        this.acceptedAnswers = cleanList(acceptedAnswers);
        this.caseSensitive = caseSensitive;
        validateSpecific();
    }

    public List<String> getAcceptedAnswers() {
        return Collections.unmodifiableList(acceptedAnswers);
    }

    public boolean isCaseSensitive() {
        return caseSensitive;
    }

    @Override
    public QuestionType getType() {
        return QuestionType.IDENTIFICATION;
    }

    @Override
    protected String renderOptions(List<Integer> displayOrder) {
        return "";
    }

    @Override
    public String getAnswerHint() {
        return "Type your answer" + (caseSensitive ? " (capitalisation matters)." : ".");
    }

    @Override
    public List<String> parseAnswer(String raw, List<Integer> displayOrder) throws ValidationException {
        return List.of(requireAnswerText(raw));
    }

    @Override
    public String formatAnswer(List<String> values, List<Integer> displayOrder) {
        return values.isEmpty() ? "(no answer)" : values.get(0);
    }

    @Override
    protected GradeOutcome grade(List<String> values) {
        String given = values.get(0);
        for (String accepted : acceptedAnswers) {
            boolean match = caseSensitive ? collapse(accepted).equals(collapse(given))
                    : normalize(accepted).equals(normalize(given));
            if (match) {
                return GradeOutcome.of(getPoints(), getPoints());
            }
        }
        return GradeOutcome.of(0, getPoints());
    }

    @Override
    public String describeAnswerKey() {
        return "Accepted: " + String.join(" | ", acceptedAnswers) + (caseSensitive ? " (case-sensitive)" : "");
    }

    @Override
    protected void validateSpecific() throws InvalidQuestionException {
        if (acceptedAnswers.isEmpty()) {
            throw new InvalidQuestionException("Provide at least one accepted answer.");
        }
    }
}
