package ui;

import exceptions.QuizSystemException;
import models.Exam;
import models.ExamSession;
import models.Result;
import models.Role;
import models.SchoolClass;
import models.Student;
import models.Subject;
import models.User;
import services.ReportService;
import services.ServiceRegistry;
import util.TextUtil;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Report screens shared by the administrator and teacher menus. */
public class ReportScreens extends ScreenBase {

    public ReportScreens(ServiceRegistry services, InputReader in) {
        super(services, in);
    }

    public void menu(User actor) {
        List<MenuItem> items = new ArrayList<>(List.of(
                item("Examination statistics report (average, highest, lowest, pass rate)", () -> examStatistics(actor)),
                item("Examination results ranking (highest to lowest)", () -> ranking(actor)),
                item("Filter results by subject / class / exam", () -> filterResults(actor)),
                item("Class performance report", () -> classPerformance(actor)),
                item("Student attempt history", () -> studentHistory(actor))));
        if (actor.getRole() == Role.ADMIN) {
            items.add(item("System summary report", () -> systemSummary(actor)));
        }
        runMenu("RESULTS AND REPORTS", "Back", items.toArray(new MenuItem[0]));
    }

    private Exam pickExam(User actor) throws QuizSystemException {
        List<Exam> exams = services.exams().examsFor(actor, null).stream().filter(e -> !e.isDraft()).toList();
        return choose("Choose an examination", exams, this::examLabel);
    }

    private void examStatistics(User actor) throws QuizSystemException {
        Exam e = pickExam(actor);
        if (e == null) {
            return;
        }
        ReportService.ExamStatistics stats = services.reports().examStatistics(actor, e.getExamId());
        String text = services.reports().formatExamStatistics(stats);
        header("EXAMINATION STATISTICS");
        out.print(text);
        offerExport(actor, "exam_report_" + e.getExamId(), text);
    }

    private void ranking(User actor) throws QuizSystemException {
        Exam e = pickExam(actor);
        if (e == null) {
            return;
        }
        printRows("RESULTS RANKING - " + e.getTitle(), services.reports().examRanking(actor, e.getExamId()), actor,
                "ranking_" + e.getExamId());
    }

    private void filterResults(User actor) throws QuizSystemException {
        out.println("Filter by: 1. Subject  2. Class  3. Examination  4. No filter (all visible results)");
        int f = in.integer("Choose: ", 1, 4);
        String subjectId = null;
        String classId = null;
        String examId = null;
        String label = "all";
        if (f == 1) {
            List<Subject> subjects = actor.getRole() == Role.ADMIN ? services.subjects().listSubjects(null)
                    : services.subjects().subjectsForTeacher(actor.getUserId());
            Subject s = choose("Choose a subject", subjects, this::subjectLabel);
            if (s == null) {
                return;
            }
            subjectId = s.getSubjectId();
            label = s.getCode();
        } else if (f == 2) {
            SchoolClass c = choose("Choose a class", services.subjects().listClasses(), this::classLabel);
            if (c == null) {
                return;
            }
            classId = c.getClassId();
            label = c.getName();
        } else if (f == 3) {
            Exam e = pickExam(actor);
            if (e == null) {
                return;
            }
            examId = e.getExamId();
            label = e.getTitle();
        }
        printRows("RESULTS - " + label, services.reports().filterResults(actor, subjectId, classId, examId), actor,
                "results_" + label);
    }

    private void printRows(String title, List<ReportService.ResultRow> rows, User actor, String exportName)
            throws QuizSystemException {
        List<String[]> table = new ArrayList<>();
        int rank = 1;
        for (ReportService.ResultRow row : rows) {
            Result r = row.result();
            table.add(new String[]{String.valueOf(rank++), row.studentName(), row.studentNumber(), row.examTitle(),
                    String.valueOf(r.getAttemptNumber()), TextUtil.num(r.getTotalScore()) + "/" + TextUtil.num(r.getMaxScore()),
                    TextUtil.pct(r.getPercentage()), r.isFinal() ? (r.isPassed() ? "PASS" : "FAIL") : "PENDING",
                    r.isReleased() ? "Yes" : "No"});
        }
        String text = title + System.lineSeparator() + TablePrinter.render(new String[]{"Rank", "Student", "Student No.",
                "Exam", "Try", "Score", "%", "Result", "Released"}, table);
        header(title);
        out.print(text.substring(text.indexOf(System.lineSeparator()) + System.lineSeparator().length()));
        if (!rows.isEmpty()) {
            offerExport(actor, exportName, text);
        }
    }

    private void classPerformance(User actor) throws QuizSystemException {
        SchoolClass c = choose("Choose a class", services.subjects().listClasses(), this::classLabel);
        if (c == null) {
            return;
        }
        List<String[]> rows = new ArrayList<>();
        for (ReportService.StudentPerformance p : services.reports().classPerformance(actor, c.getClassId())) {
            rows.add(new String[]{p.name(), p.studentNumber(), String.valueOf(p.results()),
                    p.results() == 0 ? "-" : TextUtil.pct(p.average()), p.results() == 0 ? "-" : TextUtil.pct(p.best()),
                    p.results() == 0 ? "-" : TextUtil.pct(p.lowest()), p.passed() + "/" + p.results()});
        }
        String title = "CLASS PERFORMANCE - " + c.getName();
        String table = TablePrinter.render(new String[]{"Student", "Student No.", "Final results", "Average", "Best",
                "Lowest", "Passed"}, rows);
        header(title);
        out.print(table);
        offerExport(actor, "class_" + c.getName(), title + System.lineSeparator() + table);
    }

    private void studentHistory(User actor) throws QuizSystemException {
        Student s = choose("Choose a student", services.users().listStudents(), this::userLabel);
        if (s == null) {
            return;
        }
        List<String[]> rows = new ArrayList<>();
        for (ExamSession session : services.reports().studentHistory(actor, s.getUserId())) {
            Result r = services.grading().findResultForSession(session.getSessionId()).orElse(null);
            rows.add(new String[]{services.store().findExam(session.getExamId()).map(Exam::getTitle).orElse("?"),
                    String.valueOf(session.getAttemptNumber()), stamp(session.getStartedAt()),
                    stamp(session.getSubmittedAt() != null ? session.getSubmittedAt() : session.getExpiredAt()),
                    session.getSubmissionType() == null ? "-" : session.getSubmissionType().getLabel(),
                    session.getState().getLabel(), r == null ? "-" : TextUtil.pct(r.getPercentage()) + " " + r.getStatus().getLabel()});
        }
        header("ATTEMPT HISTORY - " + s.getFullName());
        TablePrinter.print(out, new String[]{"Exam", "Try", "Started", "Ended", "Submission", "State", "Result"}, rows);
    }

    private void systemSummary(User actor) throws QuizSystemException {
        String text = services.reports().systemSummary(actor);
        header("SYSTEM SUMMARY");
        out.print(text);
        offerExport(actor, "system_summary", text);
    }

    private void offerExport(User actor, String name, String content) throws QuizSystemException {
        if (in.yesNo("Export this report to a text file?")) {
            Path p = services.reports().export(actor, name, content);
            success("Report saved to " + p);
        }
    }
}
