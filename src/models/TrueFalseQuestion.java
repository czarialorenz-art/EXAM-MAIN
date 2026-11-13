package models;

import exceptions.InvalidQuestionException;
import exceptions.ValidationException;

import java.util.List;

/** True or False question. */
public class TrueFalseQuestion extends Question {
    private static final long serialVersionUID = 1L;

    private boolean correctAnswer;

    public TrueFalseQuestion(String questionId, String teacherId, String subjectId, String text, double points,
                             String category, Difficulty difficulty, String explanation, boolean correctAnswer)
            throws InvalidQuestionException {
        super(questionId, teacherId, subjectId, text, points, category, difficulty, explanation);
        this.correctAnswer = correctAnswer;
    }

    public boolean getCorrectAnswer() {
        return correctAnswer;
    }

    @Override
    public QuestionType getType() {
        return QuestionType.TRUE_FALSE;
    }

    @Override
    protected String renderOptions(List<Integer> displayOrder) {
        return "   (T) True    (F) False";
    }

    @Override
    public String getAnswerHint() {
        return "Type T for True or F for False.";
    }

    @Override
    public List<String> parseAnswer(String raw, List<Integer> displayOrder) throws ValidationException {
        switch (normalize(requireAnswerText(raw))) {
            case "t", "true", "y", "yes":
                return List.of("true");
            case "f", "false", "n", "no":
                return List.of("false");
            default:
                throw new ValidationException("Please answer T (True) or F (False).");
        }
    }

    @Override
    public String formatAnswer(List<String> values, List<Integer> displayOrder) {
        return values.isEmpty() ? "(no answer)" : ("true".equals(values.get(0)) ? "True" : "False");
    }

    @Override
    protected GradeOutcome grade(List<String> values) {
        boolean correct = Boolean.parseBoolean(values.get(0)) == correctAnswer;
        return GradeOutcome.of(correct ? getPoints() : 0, getPoints());
    }

    @Override
    public String describeAnswerKey() {
        return correctAnswer ? "True" : "False";
    }

    @Override
    protected void validateSpecific() {
        // A boolean key is always valid.
    }
}
