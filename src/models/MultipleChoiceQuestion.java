package models;

import exceptions.InvalidQuestionException;
import exceptions.ValidationException;

import java.util.List;

/** One correct choice; all-or-nothing. */
public class MultipleChoiceQuestion extends ChoiceQuestion {
    private static final long serialVersionUID = 1L;

    private int correctIndex;

    public MultipleChoiceQuestion(String questionId, String teacherId, String subjectId, String text, double points,
                                  String category, Difficulty difficulty, String explanation,
                                  List<String> choices, int correctIndex) throws InvalidQuestionException {
        super(questionId, teacherId, subjectId, text, points, category, difficulty, explanation, choices);
        this.correctIndex = correctIndex;
        validateSpecific();
    }

    public int getCorrectIndex() {
        return correctIndex;
    }

    @Override
    public QuestionType getType() {
        return QuestionType.MULTIPLE_CHOICE;
    }

    @Override
    public String getAnswerHint() {
        return "Type the letter of your answer (e.g. B).";
    }

    @Override
    public List<String> parseAnswer(String raw, List<Integer> displayOrder) throws ValidationException {
        return List.of(String.valueOf(toOriginal(requireAnswerText(raw), displayOrder)));
    }

    @Override
    public String formatAnswer(List<String> values, List<Integer> displayOrder) {
        return values.isEmpty() ? "(no answer)" : describeChoice(values.get(0), displayOrder);
    }

    @Override
    protected GradeOutcome grade(List<String> values) {
        boolean correct = Integer.parseInt(values.get(0)) == correctIndex;
        return GradeOutcome.of(correct ? getPoints() : 0, getPoints());
    }

    @Override
    public String describeAnswerKey() {
        return keyLabel(correctIndex);
    }

    @Override
    protected void validateSpecific() throws InvalidQuestionException {
        if (!inRange(correctIndex)) {
            throw new InvalidQuestionException("The correct answer must be one of the choices.");
        }
    }
}
