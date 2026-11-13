package models;

import exceptions.InvalidQuestionException;
import exceptions.ValidationException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Text with one or more blanks written as three or more underscores ("___").
 * Each blank has its own accepted answers; points are split evenly across blanks.
 */
public class FillInTheBlankQuestion extends Question {
    private static final long serialVersionUID = 1L;
    private static final Pattern BLANK = Pattern.compile("_{3,}");

    private List<List<String>> acceptedPerBlank = new ArrayList<>();

    public FillInTheBlankQuestion(String questionId, String teacherId, String subjectId, String text, double points,
                                  String category, Difficulty difficulty, String explanation,
                                  List<List<String>> acceptedPerBlank) throws InvalidQuestionException {
        super(questionId, teacherId, subjectId, text, points, category, difficulty, explanation);
        if (acceptedPerBlank != null) {
            for (List<String> answers : acceptedPerBlank) {
                this.acceptedPerBlank.add(cleanList(answers));
            }
        }
        validateSpecific();
    }

    public static int countBlanks(String text) {
        if (text == null) {
            return 0;
        }
        Matcher m = BLANK.matcher(text);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    public int getBlankCount() {
        return countBlanks(getText());
    }

    public List<List<String>> getAcceptedPerBlank() {
        return acceptedPerBlank.stream().map(ArrayList::new).collect(Collectors.toList());
    }

    @Override
    public QuestionType getType() {
        return QuestionType.FILL_IN_THE_BLANKS;
    }

    @Override
    protected String renderOptions(List<Integer> displayOrder) {
        int n = getBlankCount();
        return "   (" + n + (n == 1 ? " blank)" : " blanks)");
    }

    @Override
    public String getAnswerHint() {
        int n = getBlankCount();
        return n == 1 ? "Type the missing word or phrase."
                : "Type the " + n + " answers in order, separated by ';' (e.g. first; second).";
    }

    @Override
    public List<String> parseAnswer(String raw, List<Integer> displayOrder) throws ValidationException {
        requireAnswerText(raw);
        int n = getBlankCount();
        List<String> parts = Arrays.stream(raw.split(";", -1)).map(String::trim).collect(Collectors.toList());
        if (parts.size() > n) {
            throw new ValidationException("This question has only " + n + " blank(s), but you typed " + parts.size() + " answers.");
        }
        while (parts.size() < n) {
            parts.add("");
        }
        return parts;
    }

    @Override
    public String formatAnswer(List<String> values, List<Integer> displayOrder) {
        StringJoiner j = new StringJoiner("; ");
        values.forEach(v -> j.add(v.isBlank() ? "___" : v));
        return values.isEmpty() ? "(no answer)" : j.toString();
    }

    @Override
    protected GradeOutcome grade(List<String> values) {
        int n = acceptedPerBlank.size();
        int correct = 0;
        for (int i = 0; i < n && i < values.size(); i++) {
            String given = normalize(values.get(i));
            if (!given.isEmpty() && acceptedPerBlank.get(i).stream().anyMatch(a -> normalize(a).equals(given))) {
                correct++;
            }
        }
        return GradeOutcome.of(getPoints() * correct / n, getPoints());
    }

    @Override
    public String describeAnswerKey() {
        StringJoiner j = new StringJoiner("; ");
        for (int i = 0; i < acceptedPerBlank.size(); i++) {
            j.add("Blank " + (i + 1) + ": " + String.join(" | ", acceptedPerBlank.get(i)));
        }
        return j.toString();
    }

    @Override
    protected void validateSpecific() throws InvalidQuestionException {
        int n = getBlankCount();
        if (n == 0) {
            throw new InvalidQuestionException("The question text must contain at least one blank written as ___ (three underscores).");
        }
        if (acceptedPerBlank.size() != n) {
            throw new InvalidQuestionException("The text has " + n + " blank(s) but " + acceptedPerBlank.size()
                    + " answer set(s) were given.");
        }
        for (int i = 0; i < n; i++) {
            if (acceptedPerBlank.get(i).isEmpty()) {
                throw new InvalidQuestionException("Blank " + (i + 1) + " needs at least one accepted answer.");
            }
        }
    }
}
