package services;

import data.DataStore;
import exceptions.AccessDeniedException;
import exceptions.QuizSystemException;
import exceptions.ValidationException;
import models.Exam;
import models.ExamSession;
import models.ExamStatus;
import models.Question;
import models.QuestionScore;
import models.Result;
import models.Role;
import models.SessionState;
import models.Student;
import models.User;
import util.TextUtil;
import util.TimeFormatter;

import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Statistics and reports. Teachers only see data from their own exams; administrators see
 * everything; students only ever see their own results.
 */
public class ReportService {

    /** Average score on one question across final results. */
    public record QuestionStat(String questionId, String label, double averagePercent, int responses) {
    }

    public record ExamStatistics(String examId, String title, String subjectCode, double totalPoints,
                                 double passingPercentage, int assigned, int studentsSubmitted, int attemptsSubmitted,
                                 int attemptsExpired, int attemptsInProgress, int notStarted, int finalResults,
                                 int pendingResults, double average, double highest, String highestStudent,
                                 double lowest, String lowestStudent, int passed, int failed,
                                 List<QuestionStat> questionStats) {
        public double passRate() {
            return passed + failed == 0 ? 0 : passed * 100.0 / (passed + failed);
        }

        public double failRate() {
            return passed + failed == 0 ? 0 : failed * 100.0 / (passed + failed);
        }

        public double completionRate() {
            return assigned == 0 ? 0 : studentsSubmitted * 100.0 / assigned;
        }
    }

    /** One result with the names needed for display. */
    public record ResultRow(Result result, String studentName, String studentNumber, String examTitle,
                            String subjectCode) {
    }

    public record StudentPerformance(String studentId, String name, String studentNumber, int results,
                                     double average, double best, double lowest, int passed) {
    }

    private final DataStore store;
    private final Clock clock;
    private final ExamService examService;
    private final SubjectService subjectService;

    public ReportService(DataStore store, Clock clock, ExamService examService, SubjectService subjectService) {
        this.store = store;
        this.clock = clock;
        this.examService = examService;
        this.subjectService = subjectService;
    }

    // ------------------------------------------------------------------ exam statistics

    /**
     * Average, highest, lowest, pass/fail rates and completion for an exam. Uses each student's
     * best FINAL result, so students with several attempts are counted once.
     */
    public ExamStatistics examStatistics(User actor, String examId) throws QuizSystemException {
        Exam exam = examService.requireViewable(actor, examId);
        Set<String> assigned = examService.assignedStudentIds(exam);
        List<ExamSession> sessions = store.getSessions().stream().filter(s -> s.getExamId().equals(examId))
                .collect(Collectors.toList());
        List<Result> results = store.getResults().stream().filter(r -> r.getExamId().equals(examId))
                .collect(Collectors.toList());

        Map<String, Result> best = bestFinalPerStudent(results);
        int attemptsSubmitted = (int) sessions.stream().filter(s -> s.getState() == SessionState.SUBMITTED).count();
        int expired = (int) sessions.stream().filter(s -> s.getState() == SessionState.EXPIRED).count();
        int inProgress = (int) sessions.stream().filter(ExamSession::isOpen).count();
        int studentsSubmitted = (int) sessions.stream().filter(s -> s.getState() == SessionState.SUBMITTED)
                .map(ExamSession::getStudentId).distinct().count();
        Set<String> started = sessions.stream().map(ExamSession::getStudentId).collect(Collectors.toSet());
        int notStarted = (int) assigned.stream().filter(id -> !started.contains(id)).count();
        int finalCount = (int) results.stream().filter(Result::isFinal).count();

        double avg = best.values().stream().mapToDouble(Result::getPercentage).average().orElse(0);
        Result high = best.values().stream().max(Comparator.comparingDouble(Result::getPercentage)).orElse(null);
        Result low = best.values().stream().min(Comparator.comparingDouble(Result::getPercentage)).orElse(null);
        int passed = (int) best.values().stream().filter(Result::isPassed).count();

        List<QuestionStat> qStats = new ArrayList<>();
        int number = 1;
        for (Question q : exam.getQuestions()) {
            double sum = 0;
            int n = 0;
            for (Result r : results) {
                QuestionScore qs = r.getScore(q.getQuestionId());
                if (r.isFinal() && qs != null && qs.getMaxPoints() > 0) {
                    sum += qs.getEarned() / qs.getMaxPoints() * 100;
                    n++;
                }
            }
            qStats.add(new QuestionStat(q.getQuestionId(), "Q" + number++ + " " + q.getType().getLabel() + ": "
                    + TextUtil.truncate(q.getText(), 40), n == 0 ? 0 : sum / n, n));
        }

        return new ExamStatistics(exam.getExamId(), exam.getTitle(), subjectService.subjectLabel(exam.getSubjectId()),
                examService.totalPoints(exam), exam.getPassingPercentage(), assigned.size(), studentsSubmitted,
                attemptsSubmitted, expired, inProgress, notStarted, finalCount, results.size() - finalCount, avg,
                high == null ? 0 : high.getPercentage(), high == null ? "-" : studentName(high.getStudentId()),
                low == null ? 0 : low.getPercentage(), low == null ? "-" : studentName(low.getStudentId()),
                passed, best.size() - passed, qStats);
    }

    private Map<String, Result> bestFinalPerStudent(List<Result> results) {
        Map<String, Result> best = new HashMap<>();
        for (Result r : results) {
            if (r.isFinal()) {
                best.merge(r.getStudentId(), r, (a, b) -> a.getPercentage() >= b.getPercentage() ? a : b);
            }
        }
        return best;
    }

    public String formatExamStatistics(ExamStatistics s) {
        String nl = System.lineSeparator();
        StringBuilder sb = new StringBuilder();
        sb.append("EXAMINATION REPORT: ").append(s.title()).append(" (").append(s.examId()).append(", ")
                .append(s.subjectCode()).append(")").append(nl);
        sb.append("Generated: ").append(TimeFormatter.dateTime(LocalDateTime.now(clock))).append(nl);
        sb.append("Total points: ").append(TextUtil.num(s.totalPoints())).append("   Passing score: ")
                .append(TextUtil.pct(s.passingPercentage())).append(nl).append(nl);
        sb.append("COMPLETION").append(nl);
        sb.append("  Students assigned          : ").append(s.assigned()).append(nl);
        sb.append("  Students who submitted     : ").append(s.studentsSubmitted()).append(" (")
                .append(TextUtil.pct(s.completionRate())).append(")").append(nl);
        sb.append("  Students not started       : ").append(s.notStarted()).append(nl);
        sb.append("  Attempts submitted         : ").append(s.attemptsSubmitted()).append(nl);
        sb.append("  Attempts expired           : ").append(s.attemptsExpired()).append(nl);
        sb.append("  Attempts in progress       : ").append(s.attemptsInProgress()).append(nl);
        sb.append("  Results final / pending    : ").append(s.finalResults()).append(" / ").append(s.pendingResults()).append(nl).append(nl);
        sb.append("PERFORMANCE (best final attempt per student)").append(nl);
        sb.append("  Average score : ").append(TextUtil.pct(s.average())).append(nl);
        sb.append("  Highest score : ").append(TextUtil.pct(s.highest())).append("  ").append(s.highestStudent()).append(nl);
        sb.append("  Lowest score  : ").append(TextUtil.pct(s.lowest())).append("  ").append(s.lowestStudent()).append(nl);
        sb.append("  Passed        : ").append(s.passed()).append(" (").append(TextUtil.pct(s.passRate())).append(")").append(nl);
        sb.append("  Failed        : ").append(s.failed()).append(" (").append(TextUtil.pct(s.failRate())).append(")").append(nl).append(nl);
        sb.append("PER-QUESTION AVERAGE").append(nl);
        for (QuestionStat q : s.questionStats()) {
            sb.append("  ").append(String.format("%-60s %8s  (%d responses)", q.label(), TextUtil.pct(q.averagePercent()),
                    q.responses())).append(nl);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ result listings

    /** Every result of an exam, highest score first. */
    public List<ResultRow> examRanking(User actor, String examId) throws QuizSystemException {
        examService.requireViewable(actor, examId);
        return visibleResults(actor).stream().filter(r -> r.getExamId().equals(examId))
                .sorted(GradingService.BY_SCORE_DESC).map(this::toRow).collect(Collectors.toList());
    }

    /** Filters results by subject, class and/or exam (null = any); highest score first. */
    public List<ResultRow> filterResults(User actor, String subjectId, String classId, String examId)
            throws QuizSystemException {
        Set<String> classMembers = classId == null ? null : subjectService.getSchoolClass(classId).getStudentIds();
        return visibleResults(actor).stream()
                .filter(r -> examId == null || r.getExamId().equals(examId))
                .filter(r -> subjectId == null || store.findExam(r.getExamId()).map(e -> e.getSubjectId().equals(subjectId)).orElse(false))
                .filter(r -> classMembers == null || classMembers.contains(r.getStudentId()))
                .sorted(GradingService.BY_SCORE_DESC).map(this::toRow).collect(Collectors.toList());
    }

    /** Average/best/lowest of each class member over their final results, best average first. */
    public List<StudentPerformance> classPerformance(User actor, String classId) throws QuizSystemException {
        List<Student> members = subjectService.classMembers(classId);
        List<Result> visible = visibleResults(actor);
        List<StudentPerformance> rows = new ArrayList<>();
        for (Student s : members) {
            List<Result> mine = visible.stream().filter(r -> r.getStudentId().equals(s.getUserId()) && r.isFinal())
                    .collect(Collectors.toList());
            double avg = mine.stream().mapToDouble(Result::getPercentage).average().orElse(0);
            double best = mine.stream().mapToDouble(Result::getPercentage).max().orElse(0);
            double low = mine.stream().mapToDouble(Result::getPercentage).min().orElse(0);
            int passed = (int) mine.stream().filter(Result::isPassed).count();
            rows.add(new StudentPerformance(s.getUserId(), s.getFullName(), s.getStudentNumber(), mine.size(), avg, best, low, passed));
        }
        rows.sort(Comparator.comparingDouble(StudentPerformance::average).reversed()
                .thenComparing(StudentPerformance::name, String.CASE_INSENSITIVE_ORDER));
        return rows;
    }

    /** Attempt history of any student (teachers/admins) - students may only ask for themselves. */
    public List<ExamSession> studentHistory(User actor, String studentId) throws QuizSystemException {
        AccessControl.requireRole(actor, Role.ADMIN, Role.TEACHER, Role.STUDENT);
        if (actor.getRole() == Role.STUDENT && !actor.getUserId().equals(studentId)) {
            throw new AccessDeniedException("You can only view your own examination records.");
        }
        return store.getSessions().stream().filter(s -> s.getStudentId().equals(studentId))
                .filter(s -> actor.getRole() != Role.TEACHER || store.findExam(s.getExamId())
                        .map(e -> e.getTeacherId().equals(actor.getUserId())).orElse(false))
                .sorted(Comparator.comparing(ExamSession::getStartedAt).reversed()).collect(Collectors.toList());
    }

    /** The logged-in student's own results (scores are shown by the UI only when released). */
    public List<Result> resultsForStudent(User actor) throws AccessDeniedException {
        AccessControl.requireStudent(actor);
        return store.getResults().stream().filter(r -> r.getStudentId().equals(actor.getUserId()))
                .sorted(Comparator.comparing(Result::getGradedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .collect(Collectors.toList());
    }

    /** Administrator overview of the whole system. */
    public String systemSummary(User actor) throws AccessDeniedException {
        AccessControl.requireRole(actor, Role.ADMIN);
        String nl = System.lineSeparator();
        StringBuilder sb = new StringBuilder("SYSTEM SUMMARY (" + TimeFormatter.dateTime(LocalDateTime.now(clock)) + ")" + nl);
        for (Role r : Role.values()) {
            long total = store.getUsers().stream().filter(u -> u.getRole() == r).count();
            long active = store.getUsers().stream().filter(u -> u.getRole() == r && u.isActive()).count();
            sb.append(String.format("  %-15s: %d (%d active)%n", r.getLabel() + "s", total, active));
        }
        sb.append(String.format("  %-15s: %d%n", "Subjects", store.getSubjects().size()));
        sb.append(String.format("  %-15s: %d%n", "Classes", store.getClasses().size()));
        sb.append(String.format("  %-15s: %d%n", "Questions", store.getQuestions().size()));
        LocalDateTime now = LocalDateTime.now(clock);
        for (ExamStatus st : ExamStatus.values()) {
            long n = store.getExams().stream().filter(e -> e.getEffectiveStatus(now) == st).count();
            sb.append(String.format("  Exams %-9s: %d%n", st.getLabel().toLowerCase(), n));
        }
        sb.append(String.format("  %-15s: %d%n", "Exam attempts", store.getSessions().size()));
        List<Result> finals = store.getResults().stream().filter(Result::isFinal).collect(Collectors.toList());
        sb.append(String.format("  %-15s: %d final, %d awaiting manual grading%n", "Results", finals.size(),
                store.getResults().size() - finals.size()));
        sb.append(String.format("  %-15s: %s%n", "Overall average",
                TextUtil.pct(finals.stream().mapToDouble(Result::getPercentage).average().orElse(0))));
        return sb.toString();
    }

    public Path export(User actor, String name, String content) throws QuizSystemException {
        AccessControl.requireRole(actor, Role.ADMIN, Role.TEACHER);
        if (content == null || content.isBlank()) {
            throw new ValidationException("There is nothing to export.");
        }
        return store.getFiles().writeTextReport(name, content);
    }

    // ------------------------------------------------------------------ helpers

    private List<Result> visibleResults(User actor) throws AccessDeniedException {
        AccessControl.requireRole(actor, Role.ADMIN, Role.TEACHER);
        return store.getResults().stream().filter(r -> actor.getRole() == Role.ADMIN || store.findExam(r.getExamId())
                .map(e -> e.getTeacherId().equals(actor.getUserId())).orElse(false)).collect(Collectors.toList());
    }

    private ResultRow toRow(Result r) {
        User u = store.findUser(r.getStudentId()).orElse(null);
        Exam e = store.findExam(r.getExamId()).orElse(null);
        return new ResultRow(r, u == null ? "(deleted)" : u.getFullName(),
                u instanceof Student s ? s.getStudentNumber() : "-",
                e == null ? "(deleted)" : e.getTitle(), e == null ? "-" : subjectService.subjectLabel(e.getSubjectId()));
    }

    private String studentName(String id) {
        return store.findUser(id).map(User::getFullName).orElse(id);
    }
}
