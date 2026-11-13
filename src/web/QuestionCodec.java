package web;

import exceptions.InvalidQuestionException;
import exceptions.QuizSystemException;
import exceptions.ValidationException;
import models.ChoiceQuestion;
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
import models.StudentAnswer;
import models.TrueFalseQuestion;
import util.TextUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Converts the nine question types to and from JSON. Building a question goes through the
 * normal model constructors, so every rule the console enforces is enforced here too.
 */
final class QuestionCodec {
    private QuestionCodec() {
    }

    // ------------------------------------------------------------------ JSON -> Question

    static Question build(Ctx c, String questionId, String teacherId) throws QuizSystemException {
        Question q = buildType(c, questionId, teacherId);
        q.setClassId(c.optId("classId"));
        return q;
    }

    private static Question buildType(Ctx c, String questionId, String teacherId) throws QuizSystemException {
        QuestionType type = enumValue(QuestionType.class, c.req("type", "Question type"), "question type");
        String subjectId = c.req("subjectId", "Subject");
        String text = c.str("text");
        double points = c.num("points", "Points");
        String category = c.str("category");
        Difficulty difficulty = c.optId("difficulty") == null ? Difficulty.MEDIUM
                : enumValue(Difficulty.class, c.str("difficulty"), "difficulty");
        String explanation = c.str("explanation");

        switch (type) {
            case MULTIPLE_CHOICE: {
                Choices ch = choices(c);
                int correct = ch.map(c.integer("correct", "Correct answer"));
                return new MultipleChoiceQuestion(questionId, teacherId, subjectId, text, points, category, difficulty,
                        explanation, ch.items, correct);
            }
            case MULTIPLE_SELECT: {
                Choices ch = choices(c);
                List<Integer> correct = new ArrayList<>();
                for (String s : c.strList("correctList")) {
                    correct.add(ch.map((int) Ctx.asNum(s, "Correct answer")));
                }
                return new MultipleSelectQuestion(questionId, teacherId, subjectId, text, points, category, difficulty,
                        explanation, ch.items, correct, c.bool("partialCredit"));
            }
            case TRUE_FALSE:
                return new TrueFalseQuestion(questionId, teacherId, subjectId, text, points, category, difficulty,
                        explanation, c.bool("correctBool"));
            case IDENTIFICATION:
                return new IdentificationQuestion(questionId, teacherId, subjectId, text, points, category, difficulty,
                        explanation, c.strList("accepted"), c.bool("caseSensitive"));
            case FILL_IN_THE_BLANKS: {
                List<List<String>> blanks = new ArrayList<>();
                Object raw = c.body().get("blanks");
                if (raw instanceof List<?> list) {
                    for (Object o : list) {
                        blanks.add(Ctx.asStringList(o));
                    }
                }
                return new FillInTheBlankQuestion(questionId, teacherId, subjectId, text, points, category, difficulty,
                        explanation, blanks);
            }
            case ENUMERATION:
                return new EnumerationQuestion(questionId, teacherId, subjectId, text, points, category, difficulty,
                        explanation, c.strList("items"), c.bool("orderMatters"), c.bool("manualGrading"));
            case ESSAY:
                return new EssayQuestion(questionId, teacherId, subjectId, text, points, category, difficulty,
                        explanation, c.body().get("minWords") == null ? 0 : c.integer("minWords", "Minimum words"),
                        c.str("rubric"));
            case MATCHING: {
                List<String> left = c.strList("left");
                List<String> right = c.strList("right");
                List<String> key = c.strList("key");
                // Drop empty rows, keeping the key aligned with column A and pointing into column B.
                Map<Integer, Integer> rightMap = new LinkedHashMap<>();
                List<String> rightClean = new ArrayList<>();
                for (int i = 0; i < right.size(); i++) {
                    if (!right.get(i).isBlank()) {
                        rightMap.put(i, rightClean.size());
                        rightClean.add(right.get(i));
                    }
                }
                List<String> leftClean = new ArrayList<>();
                Map<Integer, Integer> answerKey = new TreeMap<>();
                for (int i = 0; i < left.size(); i++) {
                    if (left.get(i).isBlank()) {
                        continue;
                    }
                    if (i < key.size() && !key.get(i).isBlank()) {
                        Integer mapped = rightMap.get((int) Ctx.asNum(key.get(i), "Match"));
                        if (mapped != null) {
                            answerKey.put(leftClean.size(), mapped);
                        }
                    }
                    leftClean.add(left.get(i));
                }
                return new MatchingQuestion(questionId, teacherId, subjectId, text, points, category, difficulty,
                        explanation, leftClean, rightClean, answerKey);
            }
            case NUMERICAL:
                return new NumericalQuestion(questionId, teacherId, subjectId, text, points, category, difficulty,
                        explanation, c.num("value", "Correct value"),
                        c.body().get("tolerance") == null || "".equals(c.str("tolerance")) ? 0
                                : c.num("tolerance", "Tolerance"));
            default:
                throw new InvalidQuestionException("Unsupported question type.");
        }
    }

    /** Non-blank choices plus a map from the form's row numbers to the cleaned indexes. */
    private record Choices(List<String> items, Map<Integer, Integer> remap) {
        int map(int formIndex) throws InvalidQuestionException {
            Integer i = remap.get(formIndex);
            if (i == null) {
                throw new InvalidQuestionException("The correct answer must be one of the (non-empty) choices.");
            }
            return i;
        }
    }

    private static Choices choices(Ctx c) {
        List<String> raw = c.strList("choices");
        List<String> items = new ArrayList<>();
        Map<Integer, Integer> remap = new LinkedHashMap<>();
        for (int i = 0; i < raw.size(); i++) {
            if (!raw.get(i).isBlank()) {
                remap.put(i, items.size());
                items.add(raw.get(i));
            }
        }
        return new Choices(items, remap);
    }

    static <E extends Enum<E>> E enumValue(Class<E> type, String value, String label) throws ValidationException {
        try {
            return Enum.valueOf(type, value.trim().toUpperCase());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ValidationException("Unknown " + label + ": " + value);
        }
    }

    // ------------------------------------------------------------------ Question -> JSON

    /** Everything a teacher sees, including the answer key and the fields needed to edit it. */
    static Map<String, Object> full(Question q) {
        Map<String, Object> m = summary(q);
        m.put("explanation", q.getExplanation());
        m.put("answerKey", q.describeAnswerKey());
        m.put("createdBy", q.getCreatedByTeacherId());
        if (q instanceof ChoiceQuestion cq) {
            m.put("choices", cq.getChoices());
        }
        if (q instanceof MultipleChoiceQuestion mc) {
            m.put("correct", mc.getCorrectIndex());
        } else if (q instanceof MultipleSelectQuestion ms) {
            m.put("correctList", new ArrayList<>(ms.getCorrectIndexes()));
            m.put("partialCredit", ms.isPartialCredit());
        } else if (q instanceof TrueFalseQuestion tf) {
            m.put("correctBool", tf.getCorrectAnswer());
        } else if (q instanceof IdentificationQuestion id) {
            m.put("accepted", id.getAcceptedAnswers());
            m.put("caseSensitive", id.isCaseSensitive());
        } else if (q instanceof FillInTheBlankQuestion fb) {
            m.put("blanks", fb.getAcceptedPerBlank());
        } else if (q instanceof EnumerationQuestion en) {
            m.put("items", en.getExpectedItems());
            m.put("orderMatters", en.isOrderMatters());
            m.put("manualGrading", en.isManualGrading());
        } else if (q instanceof EssayQuestion es) {
            m.put("minWords", es.getMinWords());
            m.put("rubric", es.getRubric());
        } else if (q instanceof MatchingQuestion mq) {
            m.put("left", mq.getLeftItems());
            m.put("right", mq.getRightItems());
            List<Integer> key = new ArrayList<>();
            for (int i = 0; i < mq.getLeftItems().size(); i++) {
                key.add(mq.getAnswerKey().get(i));
            }
            m.put("key", key);
        } else if (q instanceof NumericalQuestion nq) {
            m.put("value", nq.getCorrectValue());
            m.put("tolerance", nq.getTolerance());
        }
        return m;
    }

    static Map<String, Object> summary(Question q) {
        return Json.obj("id", q.getQuestionId(), "type", q.getType().name(), "typeLabel", q.getType().getLabel(),
                "subjectId", q.getSubjectId(), "classId", q.getClassId(), "text", q.getText(), "points", q.getPoints(),
                "category", q.getCategory(), "difficulty", q.getDifficulty().name(),
                "manual", q.requiresManualGrading());
    }

    /**
     * What a student sees while answering: options in their personal display order, but never
     * the answer key.
     */
    static Map<String, Object> forStudent(Question q, List<Integer> order, StudentAnswer a, int number) {
        Map<String, Object> m = Json.obj("id", q.getQuestionId(), "number", number, "type", q.getType().name(),
                "typeLabel", q.getType().getLabel(), "text", q.getText(), "points", q.getPoints(),
                "hint", webHint(q));
        if (q instanceof ChoiceQuestion cq) {
            List<Integer> o = safeOrder(order, cq.getChoices().size());
            m.put("choices", o.stream().map(cq.getChoices()::get).collect(Collectors.toList()));
        } else if (q instanceof MatchingQuestion mq) {
            List<Integer> o = safeOrder(order, mq.getRightItems().size());
            m.put("left", mq.getLeftItems());
            m.put("right", o.stream().map(mq.getRightItems()::get).collect(Collectors.toList()));
        } else if (q instanceof FillInTheBlankQuestion fb) {
            m.put("blankCount", fb.getBlankCount());
        } else if (q instanceof EnumerationQuestion en) {
            m.put("itemCount", en.getExpectedItems().size());
            m.put("orderMatters", en.isOrderMatters());
        } else if (q instanceof EssayQuestion es) {
            m.put("minWords", es.getMinWords());
        }
        m.put("answer", a == null ? List.of() : toDisplay(q, a.getValues(), order));
        m.put("answered", a != null && a.isAnswered());
        m.put("flagged", a != null && a.isMarkedForReview());
        return m;
    }

    private static String webHint(Question q) {
        return switch (q.getType()) {
            case MULTIPLE_CHOICE -> "Choose one answer.";
            case MULTIPLE_SELECT -> "Select all correct answers.";
            case TRUE_FALSE -> "Choose True or False.";
            case FILL_IN_THE_BLANKS -> "Fill in each blank.";
            case ENUMERATION -> "List the items, one per box.";
            case ESSAY -> q instanceof EssayQuestion es && es.getMinWords() > 0
                    ? "Write your essay (at least " + es.getMinWords() + " words recommended)." : "Write your essay.";
            case MATCHING -> "Pick the matching item from column B for each item in column A.";
            default -> q.getAnswerHint();
        };
    }

    private static List<Integer> safeOrder(List<Integer> order, int size) {
        if (order == null || order.size() != size) {
            return IntStream.range(0, size).boxed().collect(Collectors.toList());
        }
        return order;
    }

    private static String letter(int i) {
        return String.valueOf((char) ('A' + i));
    }

    /** Stored canonical values -> what the browser form shows (display letters for choices/matching). */
    static List<String> toDisplay(Question q, List<String> values, List<Integer> order) {
        List<String> out = new ArrayList<>();
        try {
            if (q instanceof ChoiceQuestion cq) {
                List<Integer> o = safeOrder(order, cq.getChoices().size());
                for (String v : values) {
                    int pos = o.indexOf(Integer.parseInt(v));
                    if (pos >= 0) {
                        out.add(letter(pos));
                    }
                }
                return out;
            }
            if (q instanceof MatchingQuestion mq) {
                List<Integer> o = safeOrder(order, mq.getRightItems().size());
                String[] row = new String[mq.getLeftItems().size()];
                java.util.Arrays.fill(row, "");
                for (String v : values) {
                    String[] p = v.split("=");
                    int left = Integer.parseInt(p[0]);
                    int pos = o.indexOf(Integer.parseInt(p[1]));
                    if (left >= 0 && left < row.length && pos >= 0) {
                        row[left] = letter(pos);
                    }
                }
                return List.of(row);
            }
        } catch (RuntimeException e) {
            return List.of();
        }
        return new ArrayList<>(values);
    }

    /**
     * Browser values -> canonical values, reusing each type's own {@code parseAnswer}. Returns an
     * empty list when the student left the question blank.
     */
    static List<String> parseFromBrowser(Question q, List<String> values, List<Integer> order) throws ValidationException {
        List<String> nonBlank = values.stream().filter(v -> v != null && !v.isBlank()).collect(Collectors.toList());
        switch (q.getType()) {
            case FILL_IN_THE_BLANKS: {
                if (nonBlank.isEmpty()) {
                    return List.of();
                }
                int n = ((FillInTheBlankQuestion) q).getBlankCount();
                List<String> parts = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    parts.add(i < values.size() && values.get(i) != null ? values.get(i).trim() : "");
                }
                return parts;
            }
            case ENUMERATION:
                return nonBlank.stream().map(String::trim).collect(Collectors.toList());
            case MULTIPLE_SELECT:
                return nonBlank.isEmpty() ? List.of() : q.parseAnswer(String.join(",", nonBlank), order);
            case MATCHING: {
                List<String> pairs = new ArrayList<>();
                for (int i = 0; i < values.size(); i++) {
                    if (values.get(i) != null && !values.get(i).isBlank()) {
                        pairs.add((i + 1) + "-" + values.get(i).trim());
                    }
                }
                return pairs.isEmpty() ? List.of() : q.parseAnswer(String.join(", ", pairs), order);
            }
            default:
                if (nonBlank.isEmpty()) {
                    return List.of();
                }
                return q.parseAnswer(nonBlank.get(0), order);
        }
    }

    /** Short text for points, e.g. "2.5". */
    static String num(double d) {
        return TextUtil.num(d);
    }
}
