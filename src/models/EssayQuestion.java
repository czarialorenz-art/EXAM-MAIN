package models;

import exceptions.InvalidQuestionException;
import exceptions.ValidationException;

import java.util.List;

/** Free-text essay; always graded manually by the teacher. */
public class EssayQuestion extends Question {
    private static final long serialVersionUID = 1L;

    private int minWords;
    private String rubric;

    public EssayQuestion(String questionId, String teacherId, String subjectId, String text, double points,
                         String category, Difficulty difficulty, String explanation, int minWords, String rubric)
            throws InvalidQuestionException {
        super(questionId, teacherId, subjectId, text, points, category, difficulty, explanation);
        this.minWords = minWords;
        this.rubric = rubric == null ? "" : rubric.trim();
        validateSpecific();
    }

    public static int wordCount(String s) {
        return s == null || s.isBlank() ? 0 : s.trim().split("\\s+").length;
    }

    public int getMinWords() {
        return minWords;
    }

    public String getRubric() {
        return rubric;
    }

    @Override
    public QuestionType getType() {
        return QuestionType.ESSAY;
    }

    @Override
    public boolean requiresManualGrading() {
        return true;
    }

    @Override
    public boolean isLongText() {
        return true;
    }

    @Override
    protected String renderOptions(List<Integer> displayOrder) {
        return minWords > 0 ? "   (Essay - at least " + minWords + " words recommended)" : "   (Essay)";
    }

    @Override
    public String getAnswerHint() {
        return "Type your essay. You may use several lines; finish with a line containing only a dot (.)";
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
        return GradeOutcome.pending(getPoints());
    }

    @Override
    public String describeAnswerKey() {
        return "Graded manually. Rubric: " + (rubric.isEmpty() ? "(none)" : rubric);
    }

    @Override
    protected void validateSpecific() throws InvalidQuestionException {
        if (minWords < 0 || minWords > 5000) {
            throw new InvalidQuestionException("Minimum word count must be between 0 and 5000.");
        }
    }
}
