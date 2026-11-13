package models;

import exceptions.InvalidQuestionException;
import exceptions.ValidationException;
import util.TextUtil;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Abstract base of every question type (abstraction + polymorphism).
 * <p>
 * Each subclass decides how it is displayed ({@link #renderOptions}), how a typed answer is
 * parsed ({@link #parseAnswer}), and how it is graded ({@link #grade}). The rest of the system
 * only ever works with {@code Question} references, for example
 * {@code for (Question q : exam.getQuestions()) q.evaluate(answer);}.
 */
public abstract class Question implements Serializable {
    private static final long serialVersionUID = 1L;
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    public static final double MAX_POINTS = 1000;

    private final String questionId;
    private final String createdByTeacherId;
    private String subjectId;
    private String text;
    private double points;
    private String category;
    private Difficulty difficulty;
    private String explanation;
    /** Class (section) this question was written for; null means it can be used with any class. */
    private String classId;

    protected Question(String questionId, String createdByTeacherId, String subjectId, String text, double points,
                       String category, Difficulty difficulty, String explanation) throws InvalidQuestionException {
        if (questionId == null || questionId.isBlank()) {
            throw new InvalidQuestionException("Question ID is required.");
        }
        this.questionId = questionId;
        this.createdByTeacherId = createdByTeacherId;
        setSubjectId(subjectId);
        setText(text);
        setPoints(points);
        setCategory(category);
        setDifficulty(difficulty);
        setExplanation(explanation);
    }

    // ------------------------------------------------------------------ polymorphic contract

    public abstract QuestionType getType();

    /** Lines shown under the question text (choices, columns, notes). May be empty. */
    protected abstract String renderOptions(List<Integer> displayOrder);

    /** Instructions telling the student how to type an answer. */
    public abstract String getAnswerHint();

    /** Converts typed input into canonical answer values. Throws on unusable input. */
    public abstract List<String> parseAnswer(String raw, List<Integer> displayOrder) throws ValidationException;

    /** Shows stored canonical values the way the student saw them. */
    public abstract String formatAnswer(List<String> values, List<Integer> displayOrder);

    /** Grades an answered question. Unanswered questions never reach this method. */
    protected abstract GradeOutcome grade(List<String> values);

    /** Answer key text - shown to teachers only. */
    public abstract String describeAnswerKey();

    /** Type-specific validation. */
    protected abstract void validateSpecific() throws InvalidQuestionException;

    public boolean requiresManualGrading() {
        return false;
    }

    /** True for answers typed over several lines (essays). */
    public boolean isLongText() {
        return false;
    }

    /** Number of items whose display order may be shuffled (choices, matching column B). */
    public int getShuffleableCount() {
        return 0;
    }

    // ------------------------------------------------------------------ template methods

    /** Question text followed by its type-specific options. */
    public final String render(List<Integer> displayOrder) {
        String options = renderOptions(displayOrder);
        return text + (options.isEmpty() ? "" : System.lineSeparator() + options);
    }

    /**
     * Grades an answer. Unanswered questions score 0 (and never wait for manual grading).
     * A stored answer that cannot be interpreted is scored 0 instead of crashing the grader.
     */
    public final GradeOutcome evaluate(StudentAnswer answer) {
        if (answer == null || !answer.isAnswered()) {
            return GradeOutcome.of(0, points);
        }
        try {
            return grade(answer.getValues());
        } catch (NumberFormatException | IndexOutOfBoundsException e) {
            return GradeOutcome.of(0, points);
        }
    }

    /** Full validation, run before a question is saved or an exam is published. */
    public final void validate() throws InvalidQuestionException {
        setSubjectId(subjectId);
        setText(text);
        setPoints(points);
        validateSpecific();
    }

    /** Deep copy (via serialization) - used to snapshot questions into a published exam. */
    public Question copy() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
                out.writeObject(this);
            }
            try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
                return (Question) in.readObject();
            }
        } catch (IOException | ClassNotFoundException e) {
            throw new IllegalStateException("Could not copy question " + questionId, e);
        }
    }

    // ------------------------------------------------------------------ getters / validated setters

    public String getQuestionId() {
        return questionId;
    }

    public String getCreatedByTeacherId() {
        return createdByTeacherId;
    }

    public String getSubjectId() {
        return subjectId;
    }

    public final void setSubjectId(String subjectId) throws InvalidQuestionException {
        if (subjectId == null || subjectId.isBlank()) {
            throw new InvalidQuestionException("A question must belong to a subject.");
        }
        this.subjectId = subjectId;
    }

    public String getText() {
        return text;
    }

    public final void setText(String text) throws InvalidQuestionException {
        if (text == null || text.isBlank()) {
            throw new InvalidQuestionException("Question text is required.");
        }
        if (text.length() > 2000) {
            throw new InvalidQuestionException("Question text must be at most 2000 characters.");
        }
        this.text = text.trim();
    }

    public double getPoints() {
        return points;
    }

    public final void setPoints(double points) throws InvalidQuestionException {
        if (Double.isNaN(points) || points <= 0 || points > MAX_POINTS) {
            throw new InvalidQuestionException("Points must be greater than 0 and at most " + TextUtil.num(MAX_POINTS) + ".");
        }
        this.points = points;
    }

    public String getCategory() {
        return category;
    }

    public final void setCategory(String category) {
        this.category = category == null || category.isBlank() ? "General" : TextUtil.truncate(category, 50);
    }

    public Difficulty getDifficulty() {
        return difficulty;
    }

    public final void setDifficulty(Difficulty difficulty) {
        this.difficulty = difficulty == null ? Difficulty.MEDIUM : difficulty;
    }

    public String getExplanation() {
        return explanation;
    }

    public final void setExplanation(String explanation) {
        this.explanation = explanation == null ? "" : explanation.trim();
    }

    /** Class (section) the question belongs to, or null for a general question. */
    public String getClassId() {
        return classId;
    }

    public final void setClassId(String classId) {
        this.classId = classId == null || classId.isBlank() ? null : classId.trim();
    }

    // ------------------------------------------------------------------ helpers for subclasses

    /** Lower-cases, trims and collapses whitespace so "  New   York " equals "new york". */
    protected static String normalize(String s) {
        return collapse(s).toLowerCase(Locale.ROOT);
    }

    protected static String collapse(String s) {
        return s == null ? "" : WHITESPACE.matcher(s.trim()).replaceAll(" ");
    }

    protected static List<Integer> safeOrder(List<Integer> order, int size) {
        if (order == null || order.size() != size) {
            return IntStream.range(0, size).boxed().collect(Collectors.toList());
        }
        return order;
    }

    protected static String letter(int index) {
        return String.valueOf((char) ('A' + index));
    }

    /** Converts "B" into display position 1, validating the range A..(count-1). */
    protected static int parseLetter(String token, int count) throws ValidationException {
        String t = token == null ? "" : token.trim().toUpperCase(Locale.ROOT);
        if (t.length() != 1 || t.charAt(0) < 'A' || t.charAt(0) >= 'A' + count) {
            throw new ValidationException("'" + (token == null ? "" : token.trim())
                    + "' is not a valid choice. Use a letter from A to " + letter(count - 1) + ".");
        }
        return t.charAt(0) - 'A';
    }

    /** Splits "a; b ;c" into trimmed, non-empty items. */
    protected static List<String> splitList(String raw) {
        if (raw == null) {
            return new ArrayList<>();
        }
        return Arrays.stream(raw.split(";")).map(String::trim).filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(ArrayList::new));
    }

    protected static String requireAnswerText(String raw) throws ValidationException {
        if (raw == null || raw.isBlank()) {
            throw new ValidationException("Answer cannot be empty.");
        }
        return raw.trim();
    }

    protected static List<String> cleanList(List<String> items) {
        List<String> out = new ArrayList<>();
        if (items != null) {
            for (String s : items) {
                if (s != null && !s.isBlank()) {
                    out.add(s.trim());
                }
            }
        }
        return out;
    }

    @Override
    public String toString() {
        return questionId + " [" + getType().getLabel() + ", " + TextUtil.num(points) + " pt] " + TextUtil.truncate(text, 60);
    }
}
