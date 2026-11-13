package models;

import exceptions.InvalidQuestionException;
import exceptions.ValidationException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Match each numbered item in column A to a lettered item in column B. Column B may contain
 * extra distractors and may be shuffled. Points are split per correct pair.
 * Stored answer values look like "0=2" (left index = original right index).
 */
public class MatchingQuestion extends Question {
    private static final long serialVersionUID = 1L;
    private static final Pattern PAIR = Pattern.compile("(\\d+)\\s*[-=:>]?\\s*([A-Za-z])");

    private List<String> leftItems = new ArrayList<>();
    private List<String> rightItems = new ArrayList<>();
    private final TreeMap<Integer, Integer> answerKey = new TreeMap<>();

    public MatchingQuestion(String questionId, String teacherId, String subjectId, String text, double points,
                            String category, Difficulty difficulty, String explanation, List<String> leftItems,
                            List<String> rightItems, Map<Integer, Integer> answerKey) throws InvalidQuestionException {
        super(questionId, teacherId, subjectId, text, points, category, difficulty, explanation);
        this.leftItems = cleanList(leftItems);
        this.rightItems = cleanList(rightItems);
        if (answerKey != null) {
            this.answerKey.putAll(answerKey);
        }
        validateSpecific();
    }

    public List<String> getLeftItems() {
        return Collections.unmodifiableList(leftItems);
    }

    public List<String> getRightItems() {
        return Collections.unmodifiableList(rightItems);
    }

    public Map<Integer, Integer> getAnswerKey() {
        return Collections.unmodifiableMap(answerKey);
    }

    @Override
    public QuestionType getType() {
        return QuestionType.MATCHING;
    }

    @Override
    public int getShuffleableCount() {
        return rightItems.size();
    }

    @Override
    protected String renderOptions(List<Integer> displayOrder) {
        List<Integer> order = safeOrder(displayOrder, rightItems.size());
        String nl = System.lineSeparator();
        StringBuilder sb = new StringBuilder("   Column A:");
        for (int i = 0; i < leftItems.size(); i++) {
            sb.append(nl).append("     ").append(i + 1).append(". ").append(leftItems.get(i));
        }
        sb.append(nl).append("   Column B:");
        for (int pos = 0; pos < order.size(); pos++) {
            sb.append(nl).append("     ").append(letter(pos)).append(". ").append(rightItems.get(order.get(pos)));
        }
        return sb.toString();
    }

    @Override
    public String getAnswerHint() {
        return "Match each number to a letter, e.g. 1-B, 2-A, 3-C  (or type the letters in order, e.g. BAC).";
    }

    @Override
    public List<String> parseAnswer(String raw, List<Integer> displayOrder) throws ValidationException {
        String t = requireAnswerText(raw).toUpperCase();
        List<Integer> order = safeOrder(displayOrder, rightItems.size());
        TreeMap<Integer, Integer> pairs = new TreeMap<>();
        if (t.matches("[A-Z]+") && t.length() == leftItems.size()) {
            for (int i = 0; i < t.length(); i++) {
                pairs.put(i, order.get(parseLetter(String.valueOf(t.charAt(i)), rightItems.size())));
            }
        } else {
            Matcher m = PAIR.matcher(t);
            while (m.find()) {
                int left = Integer.parseInt(m.group(1));
                if (left < 1 || left > leftItems.size()) {
                    throw new ValidationException("Item " + left + " does not exist. Use numbers 1 to " + leftItems.size() + ".");
                }
                pairs.put(left - 1, order.get(parseLetter(m.group(2), rightItems.size())));
            }
            if (pairs.isEmpty()) {
                throw new ValidationException("Could not read any pairs. " + getAnswerHint());
            }
        }
        List<String> values = new ArrayList<>();
        pairs.forEach((l, r) -> values.add(l + "=" + r));
        return values;
    }

    @Override
    public String formatAnswer(List<String> values, List<Integer> displayOrder) {
        if (values.isEmpty()) {
            return "(no answer)";
        }
        List<Integer> order = safeOrder(displayOrder, rightItems.size());
        StringJoiner j = new StringJoiner(", ");
        for (String v : values) {
            try {
                String[] p = v.split("=");
                int pos = order.indexOf(Integer.parseInt(p[1]));
                j.add((Integer.parseInt(p[0]) + 1) + "-" + (pos < 0 ? "?" : letter(pos)));
            } catch (RuntimeException e) {
                j.add("?");
            }
        }
        return j.toString();
    }

    @Override
    protected GradeOutcome grade(List<String> values) {
        int correct = 0;
        for (String v : values) {
            String[] p = v.split("=");
            Integer expected = answerKey.get(Integer.parseInt(p[0]));
            if (expected != null && expected == Integer.parseInt(p[1])) {
                correct++;
            }
        }
        return GradeOutcome.of(getPoints() * correct / leftItems.size(), getPoints());
    }

    @Override
    public String describeAnswerKey() {
        StringJoiner j = new StringJoiner("; ");
        answerKey.forEach((l, r) -> j.add((l + 1) + ". " + leftItems.get(l) + " -> " + rightItems.get(r)));
        return j.toString();
    }

    @Override
    protected void validateSpecific() throws InvalidQuestionException {
        if (leftItems.size() < 2) {
            throw new InvalidQuestionException("A matching question needs at least 2 items in column A.");
        }
        if (rightItems.size() < leftItems.size() || rightItems.size() > 10) {
            throw new InvalidQuestionException("Column B must have at least as many items as column A (maximum 10).");
        }
        Set<String> seen = new HashSet<>();
        for (String r : rightItems) {
            if (!seen.add(normalize(r))) {
                throw new InvalidQuestionException("Duplicate item in column B: '" + r + "'.");
            }
        }
        for (int i = 0; i < leftItems.size(); i++) {
            Integer r = answerKey.get(i);
            if (r == null || r < 0 || r >= rightItems.size()) {
                throw new InvalidQuestionException("Item " + (i + 1) + " in column A needs a valid match.");
            }
        }
        if (answerKey.size() != leftItems.size()) {
            throw new InvalidQuestionException("The answer key must contain exactly one match per item in column A.");
        }
    }
}
