package ui;

import exceptions.InvalidQuestionException;
import exceptions.QuizSystemException;
import exceptions.ValidationException;
import models.Difficulty;
import models.EnumerationQuestion;
import models.EssayQuestion;
import models.FillInTheBlankQuestion;
import models.IdentificationQuestion;
import models.MatchingQuestion;
import models.MultipleChoiceQuestion;
import models.MultipleSelectQuestion;
import models.NumericalQuestion;
import models.Question;
import models.QuestionType;
import models.TrueFalseQuestion;
import services.ServiceRegistry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Console forms that collect question data and build the right Question subclass. */
public class QuestionForm extends ScreenBase {

    /** Values shared by every question type. */
    private record Common(String text, double points, String category, Difficulty difficulty, String explanation) {
    }

    public QuestionForm(ServiceRegistry services, InputReader in) {
        super(services, in);
    }

    public QuestionType chooseType() {
        return choose("Question type", Arrays.asList(QuestionType.values()), QuestionType::getLabel);
    }

    /** Collects all fields and builds a question (the constructor validates; the service validates again). */
    public Question build(QuestionType type, String id, String teacherId, String subjectId, Question defaults)
            throws QuizSystemException {
        if (type == QuestionType.FILL_IN_THE_BLANKS) {
            info("Write each blank in the question text as ___ (three underscores).");
        }
        Common c = common(defaults);
        return switch (type) {
            case MULTIPLE_CHOICE -> {
                List<String> choices = readChoices();
                int correct = letterIndex("Letter of the correct choice: ", choices.size());
                yield new MultipleChoiceQuestion(id, teacherId, subjectId, c.text(), c.points(), c.category(),
                        c.difficulty(), c.explanation(), choices, correct);
            }
            case MULTIPLE_SELECT -> {
                List<String> choices = readChoices();
                Set<Integer> correct = letterSet("Letters of ALL correct choices (e.g. A,C): ", choices.size());
                boolean partial = in.yesNo("Allow partial credit?");
                yield new MultipleSelectQuestion(id, teacherId, subjectId, c.text(), c.points(), c.category(),
                        c.difficulty(), c.explanation(), choices, correct, partial);
            }
            case TRUE_FALSE -> new TrueFalseQuestion(id, teacherId, subjectId, c.text(), c.points(), c.category(),
                    c.difficulty(), c.explanation(), in.yesNo("Is the correct answer TRUE?"));
            case IDENTIFICATION -> new IdentificationQuestion(id, teacherId, subjectId, c.text(), c.points(),
                    c.category(), c.difficulty(), c.explanation(),
                    splitSemicolons(in.required("Accepted answers, separated by ';': ")),
                    in.yesNo("Should capitalisation matter?"));
            case FILL_IN_THE_BLANKS -> {
                int blanks = FillInTheBlankQuestion.countBlanks(c.text());
                if (blanks == 0) {
                    throw new InvalidQuestionException("The question text must contain at least one blank written as ___.");
                }
                List<List<String>> accepted = new ArrayList<>();
                for (int i = 1; i <= blanks; i++) {
                    accepted.add(splitSemicolons(in.required("Accepted answers for blank " + i + " (separate with ';'): ")));
                }
                yield new FillInTheBlankQuestion(id, teacherId, subjectId, c.text(), c.points(), c.category(),
                        c.difficulty(), c.explanation(), accepted);
            }
            case ENUMERATION -> new EnumerationQuestion(id, teacherId, subjectId, c.text(), c.points(), c.category(),
                    c.difficulty(), c.explanation(), splitSemicolons(in.required("Expected items, separated by ';': ")),
                    in.yesNo("Must the items be in this exact order?"),
                    in.yesNo("Grade manually instead of automatically?"));
            case ESSAY -> new EssayQuestion(id, teacherId, subjectId, c.text(), c.points(), c.category(),
                    c.difficulty(), c.explanation(), in.integer("Recommended minimum words (0 = none): ", 0, 5000),
                    in.line("Grading rubric (optional): "));
            case MATCHING -> buildMatching(id, teacherId, subjectId, c);
            case NUMERICAL -> new NumericalQuestion(id, teacherId, subjectId, c.text(), c.points(), c.category(),
                    c.difficulty(), c.explanation(), in.decimal("Correct value: ", -1e12, 1e12),
                    in.decimal("Accepted tolerance (+/-, 0 = exact): ", 0, 1e12));
        };
    }

    private Common common(Question d) {
        String text = d == null ? in.required("Question text: ") : in.optional("Question text", d.getText());
        double points = d == null ? in.decimal("Points (e.g. 1): ", 0.01, Question.MAX_POINTS)
                : in.decimalOrKeep("Points", d.getPoints(), 0.01, Question.MAX_POINTS);
        String category = d == null ? in.line("Category/topic (Enter = General): ") : in.optional("Category", d.getCategory());
        Difficulty difficulty = chooseDifficulty(d == null ? Difficulty.MEDIUM : d.getDifficulty());
        String explanation = d == null ? in.line("Explanation shown after release (optional): ")
                : in.optional("Explanation", d.getExplanation());
        return new Common(text, points, category, difficulty, explanation);
    }

    public Difficulty chooseDifficulty(Difficulty current) {
        out.println("Difficulty: 1. Easy  2. Medium  3. Hard   (Enter = " + current.getLabel() + ")");
        while (true) {
            String s = in.line("Difficulty: ");
            switch (s) {
                case "" -> {
                    return current;
                }
                case "1" -> {
                    return Difficulty.EASY;
                }
                case "2" -> {
                    return Difficulty.MEDIUM;
                }
                case "3" -> {
                    return Difficulty.HARD;
                }
                default -> out.println("  Please type 1, 2 or 3.");
            }
        }
    }

    private List<String> readChoices() {
        out.println("Enter the choices one per line (2-6). Press Enter on an empty line when done.");
        List<String> choices = new ArrayList<>();
        while (choices.size() < 6) {
            String s = in.line("  Choice " + (char) ('A' + choices.size()) + ": ");
            if (s.isEmpty()) {
                if (choices.size() >= 2) {
                    break;
                }
                out.println("  At least 2 choices are required.");
                continue;
            }
            choices.add(s);
        }
        return choices;
    }

    private int letterIndex(String prompt, int count) {
        while (true) {
            String s = in.line(prompt).toUpperCase();
            if (s.length() == 1 && s.charAt(0) >= 'A' && s.charAt(0) < 'A' + count) {
                return s.charAt(0) - 'A';
            }
            out.println("  Please type a letter from A to " + (char) ('A' + count - 1) + ".");
        }
    }

    private Set<Integer> letterSet(String prompt, int count) {
        while (true) {
            String s = in.line(prompt).toUpperCase().replaceAll("[,\\s]+", "");
            Set<Integer> set = new LinkedHashSet<>();
            boolean ok = !s.isEmpty();
            for (char ch : s.toCharArray()) {
                if (ch < 'A' || ch >= 'A' + count) {
                    ok = false;
                    break;
                }
                set.add(ch - 'A');
            }
            if (ok) {
                return set;
            }
            out.println("  Please type letters from A to " + (char) ('A' + count - 1) + ", e.g. A,C.");
        }
    }

    private Question buildMatching(String id, String teacherId, String subjectId, Common c) throws InvalidQuestionException {
        out.println("Column A (items to match) - one per line, empty line when done (at least 2):");
        List<String> left = readLines("  A", 2, 10);
        out.println("Column B (answers, may include extra distractors) - one per line, empty line when done:");
        List<String> right = readLines("  B", left.size(), 10);
        for (int i = 0; i < right.size(); i++) {
            out.println("    " + (char) ('A' + i) + ". " + right.get(i));
        }
        Map<Integer, Integer> key = new HashMap<>();
        for (int i = 0; i < left.size(); i++) {
            key.put(i, letterIndex("  Letter matching '" + left.get(i) + "': ", right.size()));
        }
        return new MatchingQuestion(id, teacherId, subjectId, c.text(), c.points(), c.category(), c.difficulty(),
                c.explanation(), left, right, key);
    }

    private List<String> readLines(String label, int min, int max) {
        List<String> items = new ArrayList<>();
        while (items.size() < max) {
            String s = in.line(label + (items.size() + 1) + ": ");
            if (s.isEmpty()) {
                if (items.size() >= min) {
                    break;
                }
                out.println("  At least " + min + " item(s) required.");
                continue;
            }
            items.add(s);
        }
        return items;
    }

    private static List<String> splitSemicolons(String s) {
        List<String> list = new ArrayList<>();
        for (String part : s.split(";")) {
            if (!part.isBlank()) {
                list.add(part.trim());
            }
        }
        return list;
    }

    /** Edits text, points, category, difficulty and explanation of a copy (validated by setters). */
    public void editCommonFields(Question copy) throws ValidationException, InvalidQuestionException {
        Common c = common(copy);
        copy.setText(c.text());
        copy.setPoints(c.points());
        copy.setCategory(c.category());
        copy.setDifficulty(c.difficulty());
        copy.setExplanation(c.explanation());
    }
}
