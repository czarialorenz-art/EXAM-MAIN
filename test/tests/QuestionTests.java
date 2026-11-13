package tests;

import exceptions.InvalidQuestionException;
import exceptions.ValidationException;
import models.Difficulty;
import models.EnumerationQuestion;
import models.EssayQuestion;
import models.FillInTheBlankQuestion;
import models.GradeOutcome;
import models.IdentificationQuestion;
import models.MatchingQuestion;
import models.MultipleChoiceQuestion;
import models.MultipleSelectQuestion;
import models.NumericalQuestion;
import models.Question;
import models.StudentAnswer;
import models.TrueFalseQuestion;

import java.util.List;
import java.util.Map;

import static tests.TestRunner.check;
import static tests.TestRunner.eq;
import static tests.TestRunner.expectThrows;
import static tests.TestRunner.near;
import static tests.TestRunner.suite;
import static tests.TestRunner.test;

final class QuestionTests {
    private QuestionTests() {
    }

    private static final String T = "TCH-0001";
    private static final String S = "SUB-0001";

    /** Parses typed input the way the exam screen does, then grades it. */
    private static GradeOutcome answer(Question q, String typed) throws ValidationException {
        StudentAnswer a = new StudentAnswer(q.getQuestionId());
        a.setValues(q.parseAnswer(typed, null), null);
        return q.evaluate(a);
    }

    static void run() {
        suite("Question types and polymorphic grading");
        test("Multiple choice: correct letter scores, wrong letter scores 0", () -> {
            Question q = new MultipleChoiceQuestion("Q1", T, S, "2+2?", 2, "", Difficulty.EASY, "", List.of("3", "4", "5"), 1);
            near(2, answer(q, "B").getEarned(), "correct");
            near(0, answer(q, "c").getEarned(), "wrong");
            expectThrows(ValidationException.class, () -> q.parseAnswer("Z", null));
        });
        test("Multiple choice with shuffled choices maps the displayed letter back", () -> {
            Question q = new MultipleChoiceQuestion("Q1", T, S, "2+2?", 1, "", Difficulty.EASY, "", List.of("3", "4", "5"), 1);
            List<Integer> order = List.of(2, 0, 1); // displayed: A=5, B=3, C=4
            StudentAnswer a = new StudentAnswer("Q1");
            a.setValues(q.parseAnswer("C", order), null);
            near(1, q.evaluate(a).getEarned(), "C is '4' in the shuffled order");
            eq("C. 4", q.formatAnswer(a.getValues(), order), "shown as the student saw it");
        });
        test("Multiple select: all-or-nothing and partial credit", () -> {
            Question strict = new MultipleSelectQuestion("Q2", T, S, "Primes?", 4, "", Difficulty.MEDIUM, "",
                    List.of("2", "3", "4", "5"), List.of(0, 1, 3), false);
            near(4, answer(strict, "A,B,D").getEarned(), "exact");
            near(0, answer(strict, "A,B").getEarned(), "incomplete = 0 without partial credit");
            Question partial = new MultipleSelectQuestion("Q3", T, S, "Primes?", 3, "", Difficulty.MEDIUM, "",
                    List.of("2", "3", "4", "5"), List.of(0, 1, 3), true);
            near(2, answer(partial, "AB").getEarned(), "2 of 3 right");
            near(1, answer(partial, "A B C").getEarned(), "2 right - 1 wrong = 1 of 3");
            near(0, answer(partial, "C").getEarned(), "never negative");
        });
        test("True/False accepts T/F/true/false", () -> {
            Question q = new TrueFalseQuestion("Q4", T, S, "Java is OOP.", 1, "", Difficulty.EASY, "", true);
            near(1, answer(q, "t").getEarned(), "t");
            near(1, answer(q, "TRUE").getEarned(), "TRUE");
            near(0, answer(q, "f").getEarned(), "f");
            expectThrows(ValidationException.class, () -> q.parseAnswer("maybe", null));
        });
        test("Identification: accepted answers, case/space-insensitive by default", () -> {
            Question q = new IdentificationQuestion("Q5", T, S, "Capital of PH?", 1, "", Difficulty.EASY, "",
                    List.of("Manila", "City of Manila"), false);
            near(1, answer(q, "  manila ").getEarned(), "normalised");
            near(1, answer(q, "city   of manila").getEarned(), "second accepted answer");
            near(0, answer(q, "Cebu").getEarned(), "wrong");
            Question cs = new IdentificationQuestion("Q6", T, S, "Symbol?", 1, "", Difficulty.EASY, "", List.of("Na"), true);
            near(0, answer(cs, "NA").getEarned(), "case-sensitive");
        });
        test("Fill in the blanks: per-blank partial scoring", () -> {
            Question q = new FillInTheBlankQuestion("Q7", T, S, "___ is the parent and ___ the child.", 2, "",
                    Difficulty.EASY, "", List.of(List.of("superclass", "parent class"), List.of("subclass")));
            near(2, answer(q, "Superclass; subclass").getEarned(), "both");
            near(1, answer(q, "parent class; wrong").getEarned(), "one of two");
            expectThrows(ValidationException.class, () -> q.parseAnswer("a;b;c", null));
        });
        test("Enumeration: any order, duplicates do not count twice, extras ignored", () -> {
            Question q = new EnumerationQuestion("Q8", T, S, "List 3 primary colours", 3, "", Difficulty.EASY, "",
                    List.of("red", "blue", "yellow"), false, false);
            near(3, answer(q, "Yellow; red; blue").getEarned(), "any order");
            near(1, answer(q, "red; red; red").getEarned(), "duplicates");
            near(2, answer(q, "red; blue; green; yellow").getEarned(), "only first 3 count");
            Question ordered = new EnumerationQuestion("Q9", T, S, "Order", 2, "", Difficulty.EASY, "",
                    List.of("one", "two"), true, false);
            near(0, answer(ordered, "two; one").getEarned(), "order matters");
            Question manual = new EnumerationQuestion("Q10", T, S, "Explain", 2, "", Difficulty.EASY, "",
                    List.of("x"), false, true);
            check(answer(manual, "anything").isPendingManual(), "manual enumeration is pending");
        });
        test("Essay is always pending manual grading when answered; unanswered scores 0", () -> {
            Question q = new EssayQuestion("Q11", T, S, "Discuss.", 5, "", Difficulty.HARD, "", 10, "rubric");
            check(answer(q, "Some words here").isPendingManual(), "pending");
            check(!q.evaluate(null).isPendingManual(), "unanswered is not pending");
            near(0, q.evaluate(null).getEarned(), "unanswered = 0");
            check(q.requiresManualGrading() && q.isLongText(), "flags");
        });
        test("Matching: pair syntax, letter sequence, partial credit, shuffled column B", () -> {
            Question q = new MatchingQuestion("Q12", T, S, "Match", 3, "", Difficulty.MEDIUM, "",
                    List.of("Dog", "Cat", "Cow"), List.of("Bark", "Meow", "Moo", "Quack"), Map.of(0, 0, 1, 1, 2, 2));
            near(3, answer(q, "1-A, 2-B, 3-C").getEarned(), "pairs");
            near(3, answer(q, "abc").getEarned(), "letter sequence");
            near(1, answer(q, "1A 2C 3D").getEarned(), "one correct pair");
            List<Integer> order = List.of(3, 2, 1, 0); // displayed A=Quack B=Moo C=Meow D=Bark
            StudentAnswer a = new StudentAnswer("Q12");
            a.setValues(q.parseAnswer("1-D, 2-C, 3-B", order), null);
            near(3, q.evaluate(a).getEarned(), "shuffled mapping");
            expectThrows(ValidationException.class, () -> q.parseAnswer("9-A", null));
        });
        test("Numerical: exact and tolerance", () -> {
            Question q = new NumericalQuestion("Q13", T, S, "pi?", 1, "", Difficulty.EASY, "", 3.14159, 0.01);
            near(1, answer(q, "3.14").getEarned(), "within tolerance");
            near(0, answer(q, "3.2").getEarned(), "outside tolerance");
            expectThrows(ValidationException.class, () -> q.parseAnswer("three", null));
        });
        test("polymorphism: one loop grades every type through Question references", () -> {
            List<Question> all = List.of(
                    new MultipleChoiceQuestion("A", T, S, "x", 1, "", null, "", List.of("a", "b"), 0),
                    new TrueFalseQuestion("B", T, S, "x", 1, "", null, "", true),
                    new EssayQuestion("C", T, S, "x", 1, "", null, "", 0, ""),
                    new NumericalQuestion("D", T, S, "x", 1, "", null, "", 1, 0));
            List<String> inputs = List.of("A", "T", "essay text", "1");
            double total = 0;
            int pending = 0;
            for (int i = 0; i < all.size(); i++) {
                GradeOutcome g = answer(all.get(i), inputs.get(i));
                total += g.getEarned();
                pending += g.isPendingManual() ? 1 : 0;
            }
            near(3, total, "three auto-graded correct answers");
            eq(1, pending, "essay pending");
        });

        suite("Question validation");
        test("invalid questions are rejected with clear messages", () -> {
            expectThrows(InvalidQuestionException.class, () -> new MultipleChoiceQuestion("X", T, S, "x", 1, "", null, "", List.of("only"), 0));
            expectThrows(InvalidQuestionException.class, () -> new MultipleChoiceQuestion("X", T, S, "x", 1, "", null, "", List.of("a", "b"), 5));
            expectThrows(InvalidQuestionException.class, () -> new MultipleChoiceQuestion("X", T, S, "x", 1, "", null, "", List.of("a", "A "), 0));
            expectThrows(InvalidQuestionException.class, () -> new TrueFalseQuestion("X", T, S, "  ", 1, "", null, "", true));
            expectThrows(InvalidQuestionException.class, () -> new TrueFalseQuestion("X", T, S, "x", 0, "", null, "", true));
            expectThrows(InvalidQuestionException.class, () -> new FillInTheBlankQuestion("X", T, S, "no blanks", 1, "", null, "", List.of(List.of("a"))));
            expectThrows(InvalidQuestionException.class, () -> new IdentificationQuestion("X", T, S, "x", 1, "", null, "", List.of(" "), false));
            expectThrows(InvalidQuestionException.class, () -> new NumericalQuestion("X", T, S, "x", 1, "", null, "", Double.NaN, 0));
            expectThrows(InvalidQuestionException.class, () -> new MatchingQuestion("X", T, S, "x", 1, "", null, "",
                    List.of("a", "b"), List.of("1", "2"), Map.of(0, 0)));
        });
        test("teacher can add, edit (copy) and delete bank questions", () -> {
            Fixture f = new Fixture();
            MultipleChoiceQuestion q = f.mcq("Original?", 0, 1);
            Question copy = f.s.questions().getEditableCopy(f.teacher, q.getQuestionId());
            copy.setText("Edited?");
            eq("Original?", f.s.questions().getQuestion(q.getQuestionId()).getText(), "copy does not change the bank");
            f.s.questions().updateQuestion(f.teacher, copy);
            eq("Edited?", f.s.questions().getQuestion(q.getQuestionId()).getText(), "after save");
            f.s.questions().deleteQuestion(f.teacher, q.getQuestionId());
            expectThrows(InvalidQuestionException.class, () -> f.s.questions().getQuestion(q.getQuestionId()));
        });
    }
}
