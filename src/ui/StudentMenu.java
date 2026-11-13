package ui;

import exceptions.QuizSystemException;
import models.Exam;
import models.ExamSession;
import models.ExamStatus;
import models.Question;
import models.QuestionScore;
import models.Result;
import models.Student;
import models.User;
import services.GradingService;
import services.ServiceRegistry;
import util.TextUtil;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Student dashboard. Every service call passes the logged-in student, so only their own data is reachable. */
public class StudentMenu extends BaseMenu {
    private final ExamInterface examInterface;
    private Student student;

    public StudentMenu(ServiceRegistry services, InputReader in) {
        super(services, in);
        this.examInterface = new ExamInterface(services, in);
    }

    @Override
    public void show(User user) {
        student = (Student) user;
        services.sessions().processExpiredSessions();
        runMenu("STUDENT MENU - " + student.getFullName() + " (" + student.getStudentNumber() + ")", "Logout",
                item("View available examinations / take an exam", this::availableExams),
                item("My results", this::myResults),
                item("My examination attempts (history)", this::history),
                item("Change my password", () -> changePassword(student)));
    }

    private String statusFor(Exam e) {
        if (services.sessions().findOpenSession(student.getUserId(), e.getExamId()).isPresent()) {
            return "IN PROGRESS";
        }
        ExamStatus st = e.getEffectiveStatus(now());
        int used = services.sessions().attemptsUsed(student.getUserId(), e.getExamId());
        if (st == ExamStatus.ACTIVE && used >= e.getMaxAttempts()) {
            return "No attempts left";
        }
        return switch (st) {
            case ACTIVE -> "OPEN";
            case PUBLISHED -> "Upcoming";
            default -> "Closed";
        };
    }

    private void availableExams() throws QuizSystemException {
        services.sessions().processExpiredSessions();
        List<Exam> exams = services.exams().availableExamsFor(student);
        header("MY EXAMINATIONS");
        List<String[]> rows = new ArrayList<>();
        for (int i = 0; i < exams.size(); i++) {
            Exam e = exams.get(i);
            rows.add(new String[]{String.valueOf(i + 1), e.getTitle(), services.subjects().subjectLabel(e.getSubjectId()),
                    statusFor(e), e.getTimer().isEnabled() ? util.TimeFormatter.human(e.getTimer().getDuration()) : "none",
                    services.sessions().attemptsUsed(student.getUserId(), e.getExamId()) + "/" + e.getMaxAttempts(),
                    date(e.getOpensAt()), date(e.getClosesAt())});
        }
        TablePrinter.print(out, new String[]{"#", "Title", "Subject", "Status", "Time limit", "Attempts", "Opens", "Closes"}, rows);
        if (exams.isEmpty()) {
            return;
        }
        int pick = in.integer("Open which exam? (0 = back): ", 0, exams.size());
        if (pick == 0) {
            return;
        }
        showInstructions(exams.get(pick - 1));
    }

    private void showInstructions(Exam exam) throws QuizSystemException {
        header("EXAM INSTRUCTIONS - " + exam.getTitle());
        printExamDetails(exam);
        int used = services.sessions().attemptsUsed(student.getUserId(), exam.getExamId());
        out.println("  Attempts used  : " + used + " of " + exam.getMaxAttempts());
        Optional<ExamSession> open = services.sessions().findOpenSession(student.getUserId(), exam.getExamId());
        out.println();
        if (open.isPresent()) {
            ExamSession s = open.get();
            out.println("  You have an attempt in progress (started " + stamp(s.getStartedAt()) + ").");
            out.println("  Time remaining: " + util.TimeFormatter.hms(s.remaining(services.clock().instant()))
                    + "  - the original deadline is kept.");
            if (!confirm("Resume this attempt now?")) {
                return;
            }
        } else {
            ExamStatus st = exam.getEffectiveStatus(LocalDateTime.now(services.clock()));
            if (st != ExamStatus.ACTIVE || used >= exam.getMaxAttempts()) {
                info(st == ExamStatus.PUBLISHED ? "This exam is not open yet." : used >= exam.getMaxAttempts()
                        ? "You have no attempts left for this exam." : "This exam is closed.");
                return;
            }
            if (exam.getTimer().isEnabled()) {
                out.println("  The timer starts as soon as you begin and keeps running even if you leave the exam"
                        + (exam.getTimer().isAllowPause() ? " (unless you pause it)." : "."));
            }
            if (!confirm("Start the examination now?")) {
                return;
            }
        }
        ExamSession session = services.sessions().startOrResume(student, exam.getExamId());
        examInterface.run(student, session);
    }

    private void myResults() throws QuizSystemException {
        List<Result> results = services.reports().resultsForStudent(student);
        header("MY RESULTS");
        List<String[]> rows = new ArrayList<>();
        for (int i = 0; i < results.size(); i++) {
            Result r = results.get(i);
            boolean visible = GradingService.isVisibleToStudent(r, student);
            ExamSession s = services.sessions().findSession(r.getSessionId()).orElse(null);
            rows.add(new String[]{String.valueOf(i + 1), examTitle(r.getExamId()), String.valueOf(r.getAttemptNumber()),
                    s == null ? "-" : stamp(s.getSubmittedAt()), s == null ? "-" : s.getSubmissionType().getLabel(),
                    visible ? TextUtil.num(r.getTotalScore()) + "/" + TextUtil.num(r.getMaxScore()) : "-",
                    visible ? TextUtil.pct(r.getPercentage()) : "-",
                    visible ? (r.isPassed() ? "PASSED" : "FAILED")
                            : (r.isFinal() ? "Not released yet" : "Awaiting grading")});
        }
        TablePrinter.print(out, new String[]{"#", "Exam", "Attempt", "Submitted", "Type", "Score", "%", "Result"}, rows);
        if (results.isEmpty()) {
            return;
        }
        int pick = in.integer("View details of which result? (0 = back): ", 0, results.size());
        if (pick == 0) {
            return;
        }
        Result r = results.get(pick - 1);
        if (!GradingService.isVisibleToStudent(r, student)) {
            info("This result has not been released yet.");
            return;
        }
        showResultDetails(r);
    }

    private void showResultDetails(Result r) throws QuizSystemException {
        Exam exam = services.exams().getExam(r.getExamId());
        ExamSession s = services.sessions().findSession(r.getSessionId()).orElse(null);
        header("RESULT - " + exam.getTitle() + " (attempt " + r.getAttemptNumber() + ")");
        out.println("  Score: " + TextUtil.num(r.getTotalScore()) + " / " + TextUtil.num(r.getMaxScore()) + " ("
                + TextUtil.pct(r.getPercentage()) + ")  -  " + (r.isPassed() ? "PASSED" : "FAILED")
                + "  (passing score " + TextUtil.pct(exam.getPassingPercentage()) + ")");
        if (s != null) {
            out.println("  Submitted " + stamp(s.getSubmittedAt()) + " (" + s.getSubmissionType().getLabel() + ")");
        }
        if (!r.getOverallFeedback().isEmpty()) {
            out.println("  Teacher feedback: " + r.getOverallFeedback());
        }
        List<Question> questions = s == null ? exam.getQuestions() : services.sessions().questionsInOrder(s, exam);
        int n = 1;
        for (Question q : questions) {
            QuestionScore qs = r.getScore(q.getQuestionId());
            out.println();
            out.println("  Q" + n++ + ". " + TextUtil.truncate(q.getText(), 100));
            if (s != null && s.getAnswer(q.getQuestionId()) != null && s.getAnswer(q.getQuestionId()).isAnswered()) {
                out.println("      Your answer : " + TextUtil.truncate(q.formatAnswer(s.getAnswer(q.getQuestionId()).getValues(),
                        s.getChoiceOrder(q.getQuestionId())), 120));
            } else {
                out.println("      Your answer : (not answered)");
            }
            if (qs != null) {
                out.println("      Points      : " + TextUtil.num(qs.getEarned()) + " / " + TextUtil.num(qs.getMaxPoints()));
                if (!qs.getFeedback().isEmpty()) {
                    out.println("      Feedback    : " + qs.getFeedback());
                }
            }
            if (!q.getExplanation().isEmpty()) {
                out.println("      Explanation : " + q.getExplanation());
            }
        }
        in.pause();
    }

    private void history() throws QuizSystemException {
        List<ExamSession> sessions = services.sessions().sessionsForStudent(student);
        header("MY EXAMINATION ATTEMPTS");
        List<String[]> rows = new ArrayList<>();
        for (ExamSession s : sessions) {
            Result r = services.grading().findResultForSession(s.getSessionId()).orElse(null);
            String result = r == null ? "-" : GradingService.isVisibleToStudent(r, student)
                    ? TextUtil.pct(r.getPercentage()) : (r.isFinal() ? "Not released" : "Awaiting grading");
            rows.add(new String[]{examTitle(s.getExamId()), String.valueOf(s.getAttemptNumber()), stamp(s.getStartedAt()),
                    stamp(s.getSubmittedAt() != null ? s.getSubmittedAt() : s.getExpiredAt()),
                    s.getSubmissionType() == null ? "-" : s.getSubmissionType().getLabel(), s.getState().getLabel(), result});
        }
        TablePrinter.print(out, new String[]{"Exam", "Attempt", "Started", "Ended", "Submission", "State", "Result"}, rows);
    }

    private String examTitle(String examId) {
        return services.store().findExam(examId).map(Exam::getTitle).orElse("(deleted exam)");
    }
}
