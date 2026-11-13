package ui;

import exceptions.QuizSystemException;
import exceptions.ValidationException;
import models.EssayQuestion;
import models.Exam;
import models.ExamSession;
import models.Question;
import models.Result;
import models.SessionState;
import models.Student;
import models.StudentAnswer;
import services.GradingService;
import services.ServiceRegistry;
import util.TextUtil;
import util.TimeFormatter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The student's examination screen: navigation, answering, review, timer display and submission. */
public class ExamInterface extends ScreenBase {

    public ExamInterface(ServiceRegistry services, InputReader in) {
        super(services, in);
    }

    public void run(Student student, ExamSession session) {
        Exam exam;
        try {
            exam = services.exams().getExam(session.getExamId());
        } catch (QuizSystemException e) {
            error(e.getMessage());
            return;
        }
        List<Question> questions = services.sessions().questionsInOrder(session, exam);
        if (questions.isEmpty()) {
            error("This examination has no questions.");
            return;
        }
        int index = Math.min(session.getCurrentIndex(), questions.size() - 1);
        ExamTimer timer = new ExamTimer(services.sessions(), session, services.clock(), out);
        timer.start();
        try {
            while (true) {
                if (!session.isOpen()) {
                    showEnding(session);
                    return;
                }
                try {
                    if (services.sessions().autoSubmitIfDue(session.getSessionId())) {
                        continue;
                    }
                } catch (QuizSystemException e) {
                    error(e.getMessage());
                }
                printQuestion(session, exam, questions, index);
                String cmd = in.line("Command: ").toUpperCase(Locale.ROOT);
                if (!session.isOpen()) {
                    continue; // the timer finished the attempt while we were waiting for input
                }
                try {
                    switch (cmd) {
                        case "A" -> {
                            if (answer(student, session, questions.get(index)) && index < questions.size() - 1) {
                                index++;
                            }
                        }
                        case "N" -> {
                            if (index < questions.size() - 1) {
                                index++;
                            } else {
                                info("This is the last question. Use R to review or S to submit.");
                            }
                        }
                        case "P" -> {
                            if (index > 0) {
                                index--;
                            } else {
                                info("This is the first question.");
                            }
                        }
                        case "G" -> index = in.integer("Go to question # (1-" + questions.size() + "): ", 1, questions.size()) - 1;
                        case "F" -> toggleFlag(student, session, questions.get(index));
                        case "C" -> clear(student, session, questions.get(index));
                        case "R" -> {
                            int target = review(session, questions);
                            if (target >= 0) {
                                index = target;
                            }
                        }
                        case "T" -> info("Time remaining: " + remainingText(session));
                        case "Z" -> pause(student, session);
                        case "S" -> submit(student, session, questions);
                        case "Q" -> {
                            info("Your answers are saved. " + (session.getDeadline() == null ? ""
                                    : "The timer keeps running while you are away (deadline "
                                    + stamp(session.getDeadline()) + ")."));
                            return;
                        }
                        case "" -> {
                        }
                        default -> error("Unknown command '" + cmd + "'.");
                    }
                    services.sessions().recordPosition(student, session.getSessionId(), index);
                } catch (QuizSystemException e) {
                    error(e.getMessage());
                }
            }
        } finally {
            timer.stop();
        }
    }

    private void printQuestion(ExamSession s, Exam exam, List<Question> questions, int index) {
        Question q = questions.get(index);
        StudentAnswer a = s.getAnswer(q.getQuestionId());
        List<Integer> order = s.getChoiceOrder(q.getQuestionId());
        out.println();
        out.println("-".repeat(72));
        out.printf(" %s%n", TextUtil.truncate(exam.getTitle(), 45));
        out.printf(" Time left: %s  |  Question %d of %d  |  Answered %d  |  Flagged %d%s%n",
                remainingText(s), index + 1, questions.size(), s.countAnswered(), s.countFlagged(),
                a != null && a.isMarkedForReview() ? "  |  [FLAGGED]" : "");
        out.println("-".repeat(72));
        out.printf(" Q%d. (%s pt, %s)%n", index + 1, TextUtil.num(q.getPoints()), q.getType().getLabel());
        for (String line : q.render(order).split("\\R")) {
            out.println(" " + line);
        }
        out.println();
        if (a != null && a.isAnswered()) {
            out.println(" Your answer: " + TextUtil.truncate(q.formatAnswer(a.getValues(), order), 200)
                    + "   (saved " + stamp(a.getLastSavedAt()) + ")");
        } else {
            out.println(" Your answer: (not answered)");
        }
        out.println("-".repeat(72));
        out.println(" [A]nswer [N]ext [P]rev [G]o to [F]lag [C]lear [R]eview [T]ime [Z]pause [S]ubmit [Q]uit");
    }

    private String remainingText(ExamSession s) {
        Duration d = s.remaining(services.clock().instant());
        if (d == null) {
            return "no time limit";
        }
        return TimeFormatter.hms(d) + (s.getState() == SessionState.PAUSED ? " (paused)" : "");
    }

    /** Returns true when an answer was saved. Asks before replacing a different saved answer. */
    private boolean answer(Student student, ExamSession s, Question q) throws QuizSystemException {
        info(q.getAnswerHint());
        String raw = q.isLongText()
                ? in.multiline("Type your answer below. Finish with a line containing only '.' (a lone '.' cancels):")
                : in.line("Your answer (Enter = cancel): ");
        if (raw.isBlank()) {
            info("No change made.");
            return false;
        }
        List<Integer> order = s.getChoiceOrder(q.getQuestionId());
        List<String> values;
        try {
            values = q.parseAnswer(raw, order);
        } catch (ValidationException e) {
            error(e.getMessage());
            return false;
        }
        StudentAnswer existing = s.getAnswer(q.getQuestionId());
        if (existing != null && existing.isAnswered() && !existing.getValues().equals(values)) {
            out.println("  Saved answer : " + TextUtil.truncate(q.formatAnswer(existing.getValues(), order), 150));
            out.println("  New answer   : " + TextUtil.truncate(q.formatAnswer(values, order), 150));
            if (!in.yesNo("Replace your saved answer?")) {
                info("Your previous answer was kept.");
                return false;
            }
        }
        services.sessions().saveAnswer(student, s.getSessionId(), q.getQuestionId(), values);
        if (q instanceof EssayQuestion essay && essay.getMinWords() > 0
                && EssayQuestion.wordCount(raw) < essay.getMinWords()) {
            info("Note: your essay has " + EssayQuestion.wordCount(raw) + " words; at least " + essay.getMinWords()
                    + " are recommended.");
        }
        success("Answer saved.");
        return true;
    }

    private void toggleFlag(Student student, ExamSession s, Question q) throws QuizSystemException {
        StudentAnswer a = s.getAnswer(q.getQuestionId());
        boolean flagged = a == null || !a.isMarkedForReview();
        services.sessions().setFlag(student, s.getSessionId(), q.getQuestionId(), flagged);
        success(flagged ? "Question flagged for review." : "Flag removed.");
    }

    private void clear(Student student, ExamSession s, Question q) throws QuizSystemException {
        StudentAnswer a = s.getAnswer(q.getQuestionId());
        if (a == null || !a.isAnswered()) {
            info("This question has no saved answer.");
            return;
        }
        if (in.yesNo("Clear your saved answer for this question?")) {
            services.sessions().clearAnswer(student, s.getSessionId(), q.getQuestionId());
            success("Answer cleared.");
        }
    }

    /** Shows answered/unanswered/flagged status; returns the chosen question index or -1. */
    private int review(ExamSession s, List<Question> questions) {
        List<String[]> rows = new ArrayList<>();
        for (int i = 0; i < questions.size(); i++) {
            Question q = questions.get(i);
            StudentAnswer a = s.getAnswer(q.getQuestionId());
            boolean answered = a != null && a.isAnswered();
            rows.add(new String[]{String.valueOf(i + 1), answered ? "Answered" : "UNANSWERED",
                    a != null && a.isMarkedForReview() ? "FLAGGED" : "", q.getType().getLabel(),
                    answered ? TextUtil.truncate(q.formatAnswer(a.getValues(), s.getChoiceOrder(q.getQuestionId())), 30) : "",
                    TextUtil.truncate(q.getText(), 35)});
        }
        sub("Review - " + s.countAnswered() + " of " + questions.size() + " answered, " + s.countFlagged() + " flagged");
        TablePrinter.print(out, new String[]{"#", "Status", "Flag", "Type", "Your answer", "Question"}, rows);
        String pick = in.line("Enter a question number to open it (Enter = back): ");
        if (pick.isEmpty()) {
            return -1;
        }
        try {
            int n = Integer.parseInt(pick);
            if (n >= 1 && n <= questions.size()) {
                return n - 1;
            }
        } catch (NumberFormatException ignored) {
            // fall through
        }
        error("Invalid question number.");
        return -1;
    }

    private void pause(Student student, ExamSession s) throws QuizSystemException {
        services.sessions().pause(student, s.getSessionId());
        success("Exam paused. The timer is stopped at " + remainingText(s) + ".");
        in.line("Press Enter to resume the exam...");
        services.sessions().resume(student, s.getSessionId());
        success("Resumed. Time remaining: " + remainingText(s));
    }

    private void submit(Student student, ExamSession s, List<Question> questions) throws QuizSystemException {
        int unanswered = questions.size() - s.countAnswered();
        sub("Submit examination");
        if (unanswered > 0) {
            out.println("  WARNING: " + unanswered + " question(s) are unanswered.");
        }
        if (s.countFlagged() > 0) {
            out.println("  WARNING: " + s.countFlagged() + " question(s) are flagged for review.");
        }
        out.println("  After submitting you can no longer change your answers.");
        String c = in.line("Type SUBMIT to confirm (anything else cancels): ");
        if (!c.equalsIgnoreCase("SUBMIT")) {
            info("Submission cancelled. You can keep working.");
            return;
        }
        services.sessions().submit(student, s.getSessionId());
    }

    private void showEnding(ExamSession s) {
        header(s.getState() == SessionState.SUBMITTED ? "SUBMISSION CONFIRMATION" : "EXAM ATTEMPT CLOSED");
        out.println("  " + services.sessions().describeEnding(s));
        out.println("  Reference        : " + s.getSessionId() + " (attempt " + s.getAttemptNumber() + ")");
        out.println("  Started          : " + stamp(s.getStartedAt()));
        if (s.getState() == SessionState.SUBMITTED) {
            out.println("  Submitted        : " + stamp(s.getSubmittedAt()));
            out.println("  Submission type  : " + s.getSubmissionType().getLabel().toUpperCase());
        } else {
            out.println("  Closed           : " + stamp(s.getExpiredAt()) + " (not submitted)");
        }
        Result r = services.grading().findResultForSession(s.getSessionId()).orElse(null);
        Student me = (Student) services.store().findUser(s.getStudentId()).orElse(null);
        if (r != null && GradingService.isVisibleToStudent(r, me)) {
            out.println("  Score            : " + TextUtil.num(r.getTotalScore()) + " / " + TextUtil.num(r.getMaxScore())
                    + " (" + TextUtil.pct(r.getPercentage()) + ") - " + (r.isPassed() ? "PASSED" : "FAILED"));
        } else if (r != null) {
            out.println("  Result           : will be available after your teacher "
                    + (r.isFinal() ? "releases it." : "finishes grading and releases it."));
        }
        in.pause();
    }
}
