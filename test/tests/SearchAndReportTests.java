package tests;

import models.Exam;
import models.ExamSession;
import models.MultipleChoiceQuestion;
import models.Role;
import models.User;
import services.ExamService;
import services.ReportService;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

import static tests.TestRunner.check;
import static tests.TestRunner.eq;
import static tests.TestRunner.near;
import static tests.TestRunner.suite;
import static tests.TestRunner.test;

final class SearchAndReportTests {
    private SearchAndReportTests() {
    }

    /** Alice 3/3, Bob 2/3, Carol 0/3 on a 3-question, 1-point-each exam (passing 60%). */
    private static Exam scoredExam(Fixture f) throws Exception {
        MultipleChoiceQuestion a = f.mcq("a", 0, 1);
        MultipleChoiceQuestion b = f.mcq("b", 0, 1);
        MultipleChoiceQuestion c = f.mcq("c", 0, 1);
        Exam e = f.exam("Report exam", Fixture.minutes(10, true, false), 2, a, b, c);
        answer(f, f.alice, e, List.of("0", "0", "0"), a, b, c);
        answer(f, f.bob, e, List.of("0", "0", "1"), a, b, c);
        answer(f, f.carol, e, List.of("1", "1", "1"), a, b, c);
        return e;
    }

    private static void answer(Fixture f, User st, Exam e, List<String> values, MultipleChoiceQuestion... qs) throws Exception {
        ExamSession s = f.s.sessions().startOrResume(st, e.getExamId());
        for (int i = 0; i < qs.length; i++) {
            f.s.sessions().saveAnswer(st, s.getSessionId(), qs[i].getQuestionId(), List.of(values.get(i)));
        }
        f.s.sessions().submit(st, s.getSessionId());
    }

    static void run() {
        suite("Search and sorting");
        test("search users by name, username, ID and student number", () -> {
            Fixture f = new Fixture();
            eq(List.of(f.alice), f.s.users().search("ramos", null), "by surname");
            eq(List.of(f.bob), f.s.users().search("S-002", null), "by student number");
            eq(List.of(f.teacher), f.s.users().search(f.teacher.getUserId(), null), "by ID");
            eq(3, f.s.users().search("", Role.STUDENT).size(), "role filter");
            check(f.s.users().search("zzz", null).isEmpty(), "no match");
        });
        test("users are sorted by role then name", () -> {
            Fixture f = new Fixture();
            List<String> names = f.s.users().listUsers(Role.STUDENT).stream().map(User::getFullName).collect(Collectors.toList());
            eq(List.of("Alice Ramos", "Bob Cruz", "Carol Lim"), names, "alphabetical");
        });
        test("search exams by title and sort by date/title", () -> {
            Fixture f = new Fixture();
            var q = f.mcq("q", 0, 1);
            Exam later = f.s.exams().createExam(f.teacher, f.subject.getSubjectId(), "Alpha final", "");
            f.s.exams().setSchedule(f.teacher, later.getExamId(), LocalDateTime.now(f.clock).plusDays(5), null);
            Exam sooner = f.s.exams().createExam(f.teacher, f.subject.getSubjectId(), "Zeta quiz", "");
            f.s.exams().setSchedule(f.teacher, sooner.getExamId(), LocalDateTime.now(f.clock).plusDays(1), null);
            eq(List.of(later), f.s.exams().searchByTitle(f.teacher, "FINAL"), "title search");
            eq(List.of(sooner, later), f.s.exams().examsFor(f.teacher, ExamService.BY_DATE), "by date");
            eq(List.of(later, sooner), f.s.exams().examsFor(f.teacher, ExamService.BY_TITLE), "by title");
            check(q != null, "unused");
        });
        test("search questions by category and keyword", () -> {
            Fixture f = new Fixture();
            f.mcq("Inheritance basics", 0, 1);
            f.tf("Loops run forever", true);
            eq(1, f.s.questions().searchByCategory(f.teacher, "logic").size(), "category");
            eq(1, f.s.questions().search(f.teacher, "inherit").size(), "keyword");
        });
        test("results ranking is sorted from highest to lowest", () -> {
            Fixture f = new Fixture();
            Exam e = scoredExam(f);
            List<String> order = f.s.reports().examRanking(f.teacher, e.getExamId()).stream()
                    .map(ReportService.ResultRow::studentName).collect(Collectors.toList());
            eq(List.of("Alice Ramos", "Bob Cruz", "Carol Lim"), order, "descending");
        });
        test("filter results by subject, class and exam", () -> {
            Fixture f = new Fixture();
            Exam e = scoredExam(f);
            eq(3, f.s.reports().filterResults(f.teacher, f.subject.getSubjectId(), null, null).size(), "subject");
            eq(3, f.s.reports().filterResults(f.teacher, null, f.section.getClassId(), null).size(), "class");
            eq(3, f.s.reports().filterResults(f.admin, null, null, e.getExamId()).size(), "exam (admin)");
            var other = f.s.users().createTeacher(f.admin, "teacher2", "Teacher123", "Other", "", "");
            eq(0, f.s.reports().filterResults(other, null, null, null).size(), "other teacher sees nothing");
        });

        suite("Reports");
        test("exam statistics: average, highest, lowest, pass and fail rates, completion", () -> {
            Fixture f = new Fixture();
            Exam e = scoredExam(f);
            ReportService.ExamStatistics st = f.s.reports().examStatistics(f.teacher, e.getExamId());
            near((100 + 66.6666667 + 0) / 3, st.average(), "average");
            near(100, st.highest(), "highest");
            eq("Alice Ramos", st.highestStudent(), "highest student");
            near(0, st.lowest(), "lowest");
            eq("Carol Lim", st.lowestStudent(), "lowest student");
            eq(2, st.passed(), "passed (100% and 66.7% >= 60%)");
            eq(1, st.failed(), "failed");
            near(66.6666667, st.passRate(), "pass rate");
            near(33.3333333, st.failRate(), "fail rate");
            eq(3, st.assigned(), "assigned");
            eq(3, st.studentsSubmitted(), "submitted");
            eq(0, st.notStarted(), "not started");
            check(f.s.reports().formatExamStatistics(st).contains("Average score"), "formatted");
        });
        test("statistics use each student's best attempt", () -> {
            Fixture f = new Fixture();
            Exam e = scoredExam(f);
            MultipleChoiceQuestion[] qs = e.getQuestions().toArray(new MultipleChoiceQuestion[0]);
            answer(f, f.carol, e, List.of("0", "0", "0"), qs);
            ReportService.ExamStatistics st = f.s.reports().examStatistics(f.teacher, e.getExamId());
            eq(3, st.passed(), "carol's second attempt counts");
            eq(4, st.attemptsSubmitted(), "4 attempts");
            eq(3, st.studentsSubmitted(), "3 students");
        });
        test("class performance report", () -> {
            Fixture f = new Fixture();
            scoredExam(f);
            List<ReportService.StudentPerformance> rows = f.s.reports().classPerformance(f.teacher, f.section.getClassId());
            eq("Alice Ramos", rows.get(0).name(), "best first");
            near(100, rows.get(0).average(), "alice average");
            eq("Carol Lim", rows.get(2).name(), "lowest last");
        });
        test("completion counts not-started and in-progress attempts", () -> {
            Fixture f = new Fixture();
            Exam e = f.exam("Partial", Fixture.minutes(30, true, false), 1, f.mcq("q", 0, 1));
            f.s.sessions().startOrResume(f.alice, e.getExamId());
            f.clock.advance(Duration.ofMinutes(1));
            ReportService.ExamStatistics st = f.s.reports().examStatistics(f.teacher, e.getExamId());
            eq(1, st.attemptsInProgress(), "in progress");
            eq(2, st.notStarted(), "not started");
        });
    }
}
