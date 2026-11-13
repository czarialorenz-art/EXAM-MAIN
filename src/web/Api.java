package web;

import exceptions.AccessDeniedException;
import exceptions.QuizSystemException;
import exceptions.ValidationException;
import models.Exam;
import models.ExamSession;
import models.ExamStatus;
import models.Question;
import models.QuestionScore;
import models.QuestionType;
import models.Result;
import models.Role;
import models.SchoolClass;
import models.SessionState;
import models.Student;
import models.Subject;
import models.Teacher;
import models.TimerSettings;
import models.User;
import services.AccessControl;
import services.GradingService;
import services.ReportService;
import services.ServiceRegistry;
import util.TimeFormatter;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Every JSON endpoint. Handlers only translate between JSON and the existing services; all
 * validation and permission checks stay in the service layer, exactly as for the console UI.
 */
final class Api {
    private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    private final WebServer web;
    private final ServiceRegistry s;

    private Api(WebServer web) {
        this.web = web;
        this.s = web.services();
    }

    static void register(WebServer web) {
        Api api = new Api(web);
        api.auth();
        api.admin();
        api.subjectsAndClasses();
        api.questions();
        api.exams();
        api.grading();
        api.student();
    }

    // ================================================================== authentication

    private void auth() {
        web.open("GET", "/api/status", c -> Json.obj(
                "setupNeeded", s.auth().needsInitialSetup(),
                "user", c.user() == null ? null : user(c.user()),
                "zone", s.clock().getZone().getId(),
                "serverNow", Instant.now().toEpochMilli()));

        web.open("POST", "/api/setup", c -> {
            User admin = s.auth().createInitialAdmin(c.str("username"), c.str("password"), c.str("fullName"),
                    c.str("email"));
            return new WebServer.LoginResult(web.login(admin), Json.obj("user", user(admin)));
        });

        web.open("POST", "/api/login", c -> {
            User u = s.auth().login(c.str("username"), c.str("password"));
            web.logout(c.token());
            return new WebServer.LoginResult(web.login(u), Json.obj("user", user(u)));
        });

        web.open("POST", "/api/logout", c -> {
            web.logout(c.token());
            return new WebServer.LogoutResult(Json.obj("ok", true));
        });

        web.route("POST", "/api/me/password", c -> {
            s.auth().changePassword(c.user(), c.str("current"), c.str("password"), c.str("confirm"));
            return null;
        });
    }

    // ================================================================== administrator

    private void admin() {
        web.route("GET", "/api/admin/summary", c -> Json.obj(
                "text", s.reports().systemSummary(c.user()),
                "counts", s.store().recordCounts(),
                "warnings", s.store().getLoadWarnings(),
                "dataDir", s.store().getFiles().getDataDir().toString()));

        web.route("POST", "/api/admin/backup", c -> {
            AccessControl.requireRole(c.user(), Role.ADMIN);
            return Json.obj("path", s.store().getFiles().backupAll().toString());
        });

        web.route("GET", "/api/users", c -> {
            AccessControl.requireRole(c.user(), Role.ADMIN);
            Role role = c.query("role") == null ? null : QuestionCodec.enumValue(Role.class, c.query("role"), "role");
            return s.users().search(c.query("q"), role).stream().map(this::user).collect(Collectors.toList());
        });

        web.route("POST", "/api/users", c -> {
            Role role = QuestionCodec.enumValue(Role.class, c.req("role", "Role"), "role");
            User created = switch (role) {
                case ADMIN -> s.users().createAdmin(c.user(), c.str("username"), c.str("password"), c.str("fullName"),
                        c.str("email"));
                case TEACHER -> s.users().createTeacher(c.user(), c.str("username"), c.str("password"),
                        c.str("fullName"), c.str("email"), c.str("department"));
                case STUDENT -> s.users().createStudent(c.user(), c.str("username"), c.str("password"),
                        c.str("fullName"), c.str("email"), c.str("studentNumber"), c.str("yearLevel"));
            };
            return user(created);
        });

        web.route("PUT", "/api/users/{id}", c -> {
            s.users().updateUser(c.user(), c.param("id"), c.str("username"), c.str("fullName"), c.str("email"),
                    c.str("roleDetail"));
            return user(s.users().getUser(c.param("id")));
        });

        web.route("POST", "/api/users/{id}/active", c -> {
            boolean active = c.bool("active");
            s.users().setActive(c.user(), c.param("id"), active);
            if (!active) {
                web.logoutUser(c.param("id"));
            }
            return null;
        });

        web.route("POST", "/api/users/{id}/password", c -> {
            s.users().resetPassword(c.user(), c.param("id"), c.str("password"));
            web.logoutUser(c.param("id"));
            return null;
        });

        web.route("DELETE", "/api/users/{id}", c -> {
            s.users().deleteUser(c.user(), c.param("id"));
            web.logoutUser(c.param("id"));
            return null;
        });

        web.route("GET", "/api/teachers", c -> {
            AccessControl.requireRole(c.user(), Role.ADMIN);
            return s.users().listTeachers().stream().map(this::user).collect(Collectors.toList());
        });

        web.route("GET", "/api/students", c -> {
            AccessControl.requireRole(c.user(), Role.ADMIN, Role.TEACHER);
            return s.users().listStudents().stream().map(this::studentBrief).collect(Collectors.toList());
        });
    }

    // ================================================================== subjects and classes

    private void subjectsAndClasses() {
        web.route("GET", "/api/subjects", c -> {
            User u = c.user();
            List<Subject> list = switch (u.getRole()) {
                case ADMIN -> s.subjects().searchSubjects(c.query("q"));
                case TEACHER -> s.subjects().subjectsForTeacher(u.getUserId());
                case STUDENT -> s.subjects().subjectsForStudent(u.getUserId());
            };
            return list.stream().map(this::subject).collect(Collectors.toList());
        });

        web.route("GET", "/api/subjects/{id}", c -> {
            Subject sub = requireSubjectManager(c.user(), c.param("id"));
            Map<String, Object> m = subject(sub);
            m.put("students", s.subjects().enrolledStudents(sub.getSubjectId()).stream().map(this::studentBrief)
                    .collect(Collectors.toList()));
            return m;
        });

        web.route("POST", "/api/subjects", c -> subject(s.subjects().createSubject(c.user(), c.str("code"),
                c.str("name"), c.str("description"))));

        web.route("PUT", "/api/subjects/{id}", c -> {
            s.subjects().updateSubject(c.user(), c.param("id"), c.str("code"), c.str("name"), c.str("description"));
            return subject(s.subjects().getSubject(c.param("id")));
        });

        web.route("DELETE", "/api/subjects/{id}", c -> {
            s.subjects().deleteSubject(c.user(), c.param("id"));
            return null;
        });

        web.route("POST", "/api/subjects/{id}/teacher", c -> {
            s.subjects().assignTeacher(c.user(), c.param("id"), c.optId("teacherId"));
            return null;
        });

        web.route("POST", "/api/subjects/{id}/enroll", c ->
                Json.obj("changed", s.subjects().enrollStudent(c.user(), c.param("id"), c.req("studentId", "Student"))));

        web.route("POST", "/api/subjects/{id}/unenroll", c ->
                Json.obj("changed", s.subjects().unenrollStudent(c.user(), c.param("id"), c.req("studentId", "Student"))));

        web.route("POST", "/api/subjects/{id}/enroll-class", c ->
                Json.obj("added", s.subjects().enrollClassInSubject(c.user(), c.req("classId", "Class"), c.param("id"))));

        web.route("GET", "/api/classes", c -> {
            AccessControl.requireRole(c.user(), Role.ADMIN, Role.TEACHER);
            List<Object> out = new ArrayList<>();
            for (SchoolClass sc : s.subjects().listClasses()) {
                out.add(Json.obj("id", sc.getClassId(), "name", sc.getName(), "size", sc.getStudentIds().size(),
                        "students", s.subjects().classMembers(sc.getClassId()).stream().map(this::studentBrief)
                                .collect(Collectors.toList())));
            }
            return out;
        });

        web.route("POST", "/api/classes", c -> {
            SchoolClass sc = s.subjects().createClass(c.user(), c.str("name"));
            return Json.obj("id", sc.getClassId(), "name", sc.getName());
        });

        web.route("DELETE", "/api/classes/{id}", c -> {
            s.subjects().deleteClass(c.user(), c.param("id"));
            return null;
        });

        web.route("POST", "/api/classes/{id}/add", c ->
                Json.obj("changed", s.subjects().addStudentToClass(c.user(), c.param("id"), c.req("studentId", "Student"))));

        web.route("POST", "/api/classes/{id}/remove", c ->
                Json.obj("changed", s.subjects().removeStudentFromClass(c.user(), c.param("id"),
                        c.req("studentId", "Student"))));
    }

    private Subject requireSubjectManager(User u, String subjectId) throws QuizSystemException {
        AccessControl.requireRole(u, Role.ADMIN, Role.TEACHER);
        Subject sub = s.subjects().getSubject(subjectId);
        if (u.getRole() == Role.TEACHER && !u.getUserId().equals(sub.getTeacherId())) {
            throw new AccessDeniedException("You can only view subjects assigned to you.");
        }
        return sub;
    }

    // ================================================================== question bank

    private void questions() {
        web.route("GET", "/api/questions", c -> {
            QuestionType type = c.query("type") == null ? null
                    : QuestionCodec.enumValue(QuestionType.class, c.query("type"), "question type");
            models.Difficulty diff = c.query("difficulty") == null ? null
                    : QuestionCodec.enumValue(models.Difficulty.class, c.query("difficulty"), "difficulty");
            String q = c.query("q");
            List<Question> list = s.questions().filter(c.user(), c.query("subjectId"), type, diff, null);
            if (q != null) {
                Set<String> hits = s.questions().search(c.user(), q).stream().map(Question::getQuestionId)
                        .collect(Collectors.toSet());
                list = list.stream().filter(x -> hits.contains(x.getQuestionId())).collect(Collectors.toList());
            }
            String cls = c.query("classId");
            if (cls != null) {
                // "none" = general questions that are not tied to a class
                Set<String> inClass = s.questions().forClass(c.user(), "none".equals(cls) ? null : cls).stream()
                        .map(Question::getQuestionId).collect(Collectors.toSet());
                list = list.stream().filter(x -> inClass.contains(x.getQuestionId())).collect(Collectors.toList());
            }
            return list.stream().map(this::questionFull).collect(Collectors.toList());
        });

        web.route("GET", "/api/questions/{id}", c -> {
            Question q = s.questions().getQuestion(c.param("id"));
            if (c.user().getRole() != Role.ADMIN) {
                q = s.questions().getEditableCopy(c.user(), c.param("id"));
            }
            return questionFull(q);
        });

        web.route("POST", "/api/questions", c -> {
            Teacher t = AccessControl.requireTeacher(c.user());
            Question q = QuestionCodec.build(c, s.questions().nextQuestionId(), t.getUserId());
            s.questions().addQuestion(t, q);
            return questionFull(q);
        });

        web.route("PUT", "/api/questions/{id}", c -> {
            Teacher t = AccessControl.requireTeacher(c.user());
            s.questions().getEditableCopy(t, c.param("id")); // ownership check first
            Question q = QuestionCodec.build(c, c.param("id"), t.getUserId());
            s.questions().updateQuestion(t, q);
            return questionFull(q);
        });

        web.route("DELETE", "/api/questions/{id}", c ->
                Json.obj("draftsChanged", s.questions().deleteQuestion(c.user(), c.param("id"))));
    }

    // ================================================================== exams (teacher / admin)

    private void exams() {
        web.route("GET", "/api/exams", c -> s.exams().examsFor(c.user(), null).stream().map(this::examSummary)
                .collect(Collectors.toList()));

        web.route("POST", "/api/exams", c -> examSummary(s.exams().createExam(c.user(), c.req("subjectId", "Subject"),
                c.str("title"), c.str("instructions"))));

        web.route("GET", "/api/exams/{id}", c -> {
            Exam e = s.exams().requireViewable(c.user(), c.param("id"));
            Map<String, Object> m = examSummary(e);
            m.put("instructions", e.getInstructions());
            m.put("passingPercentage", e.getPassingPercentage());
            m.put("shuffleQuestions", e.isShuffleQuestions());
            m.put("shuffleChoices", e.isShuffleChoices());
            m.put("autoReleaseResults", e.isAutoReleaseResults());
            TimerSettings t = e.getTimer();
            m.put("timer", Json.obj("enabled", t.isEnabled(),
                    "duration", t.getDuration() == null ? "" : TimeFormatter.human(t.getDuration()),
                    "minutes", t.getDuration() == null ? null : (double) t.getDuration().toMinutes(),
                    "autoSubmit", t.isAutoSubmit(), "allowPause", t.isAllowPause(), "label", t.describe()));
            m.put("questions", s.exams().questionsForPreview(c.user(), e).stream().map(this::questionFull)
                    .collect(Collectors.toList()));
            m.put("assignWholeSubject", e.isAssignWholeSubject());
            m.put("assignedStudentIds", new ArrayList<>(e.getAssignedStudentIds()));
            m.put("assignedClassIds", new ArrayList<>(e.getAssignedClassIds()));
            m.put("assignedTotal", s.exams().assignedStudentIds(e).size());
            m.put("enrolled", s.subjects().enrolledStudents(e.getSubjectId()).stream().map(this::studentBrief)
                    .collect(Collectors.toList()));
            m.put("owner", e.getTeacherId().equals(c.user().getUserId()));
            return m;
        });

        web.route("PUT", "/api/exams/{id}", c -> {
            s.exams().updateDetails(c.user(), c.param("id"), c.str("title"), c.str("instructions"),
                    c.num("passingPercentage", "Passing percentage"), c.integer("maxAttempts", "Maximum attempts"),
                    c.bool("shuffleQuestions"), c.bool("shuffleChoices"), c.bool("autoReleaseResults"));
            return null;
        });

        web.route("PUT", "/api/exams/{id}/timer", c -> {
            boolean enabled = c.bool("enabled");
            Duration d = enabled ? TimeFormatter.parseDuration(c.str("duration")) : null;
            s.exams().setTimer(c.user(), c.param("id"),
                    new TimerSettings(enabled, d, c.bool("autoSubmit"), c.bool("allowPause")));
            return null;
        });

        web.route("PUT", "/api/exams/{id}/schedule", c -> {
            s.exams().setSchedule(c.user(), c.param("id"), localTime(c.str("opensAt"), "Opening time"),
                    localTime(c.str("closesAt"), "Closing time"));
            return null;
        });

        web.route("POST", "/api/exams/{id}/questions", c -> {
            for (String qid : c.strList("questionIds")) {
                s.exams().addQuestion(c.user(), c.param("id"), qid);
            }
            return null;
        });

        web.route("DELETE", "/api/exams/{id}/questions/{qid}", c -> {
            s.exams().removeQuestion(c.user(), c.param("id"), c.param("qid"));
            return null;
        });

        web.route("POST", "/api/exams/{id}/assign", c -> {
            String exam = c.param("id");
            boolean on = c.bool("assigned");
            switch (c.req("kind", "Assignment kind")) {
                case "subject" -> s.exams().setAssignWholeSubject(c.user(), exam, on);
                case "class" -> {
                    if (on) {
                        s.exams().assignClass(c.user(), exam, c.req("targetId", "Class"));
                    } else {
                        s.exams().unassignClass(c.user(), exam, c.req("targetId", "Class"));
                    }
                }
                case "student" -> {
                    if (on) {
                        s.exams().assignStudent(c.user(), exam, c.req("targetId", "Student"));
                    } else {
                        s.exams().unassignStudent(c.user(), exam, c.req("targetId", "Student"));
                    }
                }
                default -> throw new ValidationException("Unknown assignment kind.");
            }
            return null;
        });

        web.route("POST", "/api/exams/{id}/lifecycle", c -> {
            String id = c.param("id");
            switch (c.req("action", "Action")) {
                case "publish" -> s.exams().publish(c.user(), id);
                case "unpublish" -> s.exams().unpublish(c.user(), id);
                case "close" -> {
                    s.exams().close(c.user(), id);
                    s.sessions().processExpiredSessions();
                }
                case "archive" -> s.exams().archive(c.user(), id);
                default -> throw new ValidationException("Unknown action.");
            }
            return null;
        });

        web.route("DELETE", "/api/exams/{id}", c -> {
            s.exams().delete(c.user(), c.param("id"));
            return null;
        });

        web.route("GET", "/api/exams/{id}/results", c -> {
            String id = c.param("id");
            List<Object> attempts = new ArrayList<>();
            for (ExamSession ses : s.sessions().sessionsForExam(c.user(), id)) {
                Result r = s.grading().findResultForSession(ses.getSessionId()).orElse(null);
                attempts.add(attemptRow(ses, r));
            }
            return Json.obj("ranking", s.reports().examRanking(c.user(), id).stream().map(this::resultRow)
                    .collect(Collectors.toList()), "attempts", attempts);
        });

        web.route("GET", "/api/exams/{id}/stats", c -> {
            ReportService.ExamStatistics st = s.reports().examStatistics(c.user(), c.param("id"));
            return Json.obj("title", st.title(), "subjectCode", st.subjectCode(), "totalPoints", st.totalPoints(),
                    "passingPercentage", st.passingPercentage(), "assigned", st.assigned(),
                    "studentsSubmitted", st.studentsSubmitted(), "attemptsSubmitted", st.attemptsSubmitted(),
                    "attemptsExpired", st.attemptsExpired(), "attemptsInProgress", st.attemptsInProgress(),
                    "notStarted", st.notStarted(), "finalResults", st.finalResults(),
                    "pendingResults", st.pendingResults(), "average", st.average(), "highest", st.highest(),
                    "highestStudent", st.highestStudent(), "lowest", st.lowest(), "lowestStudent", st.lowestStudent(),
                    "passed", st.passed(), "failed", st.failed(), "passRate", st.passRate(),
                    "completionRate", st.completionRate(),
                    "questions", st.questionStats().stream().map(q -> Json.obj("id", q.questionId(), "label", q.label(),
                            "average", q.averagePercent(), "responses", q.responses())).collect(Collectors.toList()),
                    "text", s.reports().formatExamStatistics(st));
        });

        web.route("POST", "/api/exams/{id}/release", c -> {
            int[] r = s.grading().releaseResults(c.user(), c.param("id"));
            return Json.obj("released", r[0], "pending", r[1]);
        });
    }

    // ================================================================== grading and reports

    private void grading() {
        web.route("GET", "/api/grading/pending", c -> {
            List<Object> out = new ArrayList<>();
            for (Result r : s.grading().pendingResults(c.user())) {
                out.add(resultBrief(r));
            }
            return out;
        });

        web.route("GET", "/api/results/{id}", c -> {
            Result r = s.grading().getResult(c.param("id"));
            Exam e = s.exams().requireViewable(c.user(), r.getExamId());
            ExamSession ses = s.sessions().findSession(r.getSessionId()).orElse(null);
            Map<String, Object> m = resultBrief(r);
            m.put("overallFeedback", r.getOverallFeedback());
            m.put("owner", e.getTeacherId().equals(c.user().getUserId()));
            m.put("submittedAt", ses == null ? "-" : TimeFormatter.stamp(ses.getSubmittedAt(), s.clock().getZone()));
            m.put("submissionType", ses == null || ses.getSubmissionType() == null ? "-"
                    : ses.getSubmissionType().getLabel());
            m.put("items", resultItems(r, e, ses, true));
            return m;
        });

        web.route("POST", "/api/results/{id}/grade", c -> {
            s.grading().gradeQuestion(c.user(), c.param("id"), c.req("questionId", "Question"),
                    c.num("points", "Points"), c.str("feedback"));
            return null;
        });

        web.route("POST", "/api/results/{id}/feedback", c -> {
            s.grading().setOverallFeedback(c.user(), c.param("id"), c.str("feedback"));
            return null;
        });

        web.route("POST", "/api/results/{id}/release", c -> {
            if (c.bool("release")) {
                s.grading().releaseResult(c.user(), c.param("id"));
            } else {
                s.grading().withdrawRelease(c.user(), c.param("id"));
            }
            return null;
        });

        web.route("GET", "/api/reports/results", c -> s.reports()
                .filterResults(c.user(), c.query("subjectId"), c.query("classId"), c.query("examId"))
                .stream().map(this::resultRow).collect(Collectors.toList()));

        web.route("GET", "/api/reports/class/{id}", c -> s.reports().classPerformance(c.user(), c.param("id")).stream()
                .map(p -> Json.obj("studentId", p.studentId(), "name", p.name(), "studentNumber", p.studentNumber(),
                        "results", p.results(), "average", p.average(), "best", p.best(), "lowest", p.lowest(),
                        "passed", p.passed()))
                .collect(Collectors.toList()));
    }

    // ================================================================== student

    private void student() {
        web.route("GET", "/api/student/exams", c -> {
            Student st = AccessControl.requireStudent(c.user());
            s.sessions().processExpiredSessions();
            List<Object> out = new ArrayList<>();
            for (Exam e : s.exams().availableExamsFor(st)) {
                Map<String, Object> m = examSummary(e);
                m.put("instructions", e.getInstructions());
                m.put("passingPercentage", e.getPassingPercentage());
                m.put("attemptsUsed", s.sessions().attemptsUsed(st.getUserId(), e.getExamId()));
                m.put("openSessionId", s.sessions().findOpenSession(st.getUserId(), e.getExamId())
                        .map(ExamSession::getSessionId).orElse(null));
                m.put("allowPause", e.getTimer().isAllowPause());
                out.add(m);
            }
            return out;
        });

        web.route("POST", "/api/student/exams/{id}/start", c ->
                sessionView(s.sessions().startOrResume(c.user(), c.param("id"))));

        web.route("GET", "/api/student/sessions/{id}", c -> {
            ExamSession ses = s.sessions().requireOwnSession(c.user(), c.param("id"));
            s.sessions().autoSubmitIfDue(ses.getSessionId());
            return sessionView(ses);
        });

        web.route("POST", "/api/student/sessions/{id}/answer", c -> {
            ExamSession ses = s.sessions().requireOwnSession(c.user(), c.param("id"));
            Exam e = s.exams().getExam(ses.getExamId());
            String qid = c.req("questionId", "Question");
            Question q = e.findQuestion(qid);
            if (q == null) {
                throw new ValidationException("That question is not part of this examination.");
            }
            List<String> values = QuestionCodec.parseFromBrowser(q, c.strList("values"), ses.getChoiceOrder(qid));
            s.sessions().saveAnswer(c.user(), ses.getSessionId(), qid, values);
            if (c.body().get("index") != null) {
                s.sessions().recordPosition(c.user(), ses.getSessionId(), c.integer("index", "Index"));
            }
            return Json.obj("answered", !values.isEmpty(),
                    "answer", QuestionCodec.toDisplay(q, values, ses.getChoiceOrder(qid)),
                    "remainingMs", remainingMs(ses));
        });

        web.route("POST", "/api/student/sessions/{id}/position", c -> {
            s.sessions().recordPosition(c.user(), c.param("id"), c.integer("index", "Index"));
            return null;
        });

        web.route("POST", "/api/student/sessions/{id}/flag", c -> {
            s.sessions().setFlag(c.user(), c.param("id"), c.req("questionId", "Question"), c.bool("flagged"));
            return null;
        });

        web.route("POST", "/api/student/sessions/{id}/pause", c -> {
            s.sessions().pause(c.user(), c.param("id"));
            return sessionView(s.sessions().requireOwnSession(c.user(), c.param("id")));
        });

        web.route("POST", "/api/student/sessions/{id}/resume", c -> {
            s.sessions().resume(c.user(), c.param("id"));
            return sessionView(s.sessions().requireOwnSession(c.user(), c.param("id")));
        });

        web.route("POST", "/api/student/sessions/{id}/submit", c -> {
            Result r = s.sessions().submit(c.user(), c.param("id"));
            return Json.obj("resultId", r.getResultId(),
                    "visible", GradingService.isVisibleToStudent(r, c.user()),
                    "final", r.isFinal());
        });

        web.route("GET", "/api/student/results", c -> {
            Student st = AccessControl.requireStudent(c.user());
            s.sessions().processExpiredSessions();
            List<Object> out = new ArrayList<>();
            for (ExamSession ses : s.sessions().sessionsForStudent(st)) {
                Result r = s.grading().findResultForSession(ses.getSessionId()).orElse(null);
                Map<String, Object> m = attemptRow(ses, r);
                boolean visible = r != null && GradingService.isVisibleToStudent(r, st);
                if (r != null && !visible) {
                    m.remove("score");
                    m.remove("maxScore");
                    m.remove("percentage");
                    m.remove("passed");
                }
                m.put("visible", visible);
                m.put("examTitle", s.store().findExam(ses.getExamId()).map(Exam::getTitle).orElse("(deleted)"));
                out.add(m);
            }
            return out;
        });

        web.route("GET", "/api/student/results/{id}", c -> {
            Student st = AccessControl.requireStudent(c.user());
            Result r = s.grading().getResult(c.param("id"));
            if (!GradingService.isVisibleToStudent(r, st)) {
                throw new AccessDeniedException("This result has not been released yet.");
            }
            Exam e = s.exams().getExam(r.getExamId());
            ExamSession ses = s.sessions().findSession(r.getSessionId()).orElse(null);
            Map<String, Object> m = resultBrief(r);
            m.put("overallFeedback", r.getOverallFeedback());
            m.put("items", resultItems(r, e, ses, false));
            return m;
        });
    }

    // ================================================================== views

    private Map<String, Object> user(User u) {
        Map<String, Object> m = Json.obj("id", u.getUserId(), "role", u.getRole().name(),
                "roleLabel", u.getRole().getLabel(), "username", u.getUsername(), "fullName", u.getFullName(),
                "email", u.getEmail(), "active", u.isActive(), "detailLabel", u.getRoleDetailLabel(),
                "detail", u.getRoleDetail());
        if (u instanceof Student st) {
            m.put("studentNumber", st.getStudentNumber());
            m.put("yearLevel", st.getYearLevel());
        } else if (u instanceof Teacher t) {
            m.put("department", t.getDepartment());
        }
        return m;
    }

    private Map<String, Object> studentBrief(Student st) {
        return Json.obj("id", st.getUserId(), "fullName", st.getFullName(), "username", st.getUsername(),
                "studentNumber", st.getStudentNumber(), "yearLevel", st.getYearLevel(), "active", st.isActive());
    }

    private Map<String, Object> subject(Subject sub) {
        return Json.obj("id", sub.getSubjectId(), "code", sub.getCode(), "name", sub.getName(),
                "description", sub.getDescription(), "teacherId", sub.getTeacherId(),
                "teacherName", s.subjects().teacherName(sub), "enrolledCount", sub.getEnrolledStudentIds().size(),
                "enrolledIds", new ArrayList<>(sub.getEnrolledStudentIds()));
    }

    private Map<String, Object> questionFull(Question q) {
        Map<String, Object> m = QuestionCodec.full(q);
        m.put("subjectCode", s.subjects().subjectLabel(q.getSubjectId()));
        m.put("className", q.getClassId() == null ? null
                : s.store().findClass(q.getClassId()).map(SchoolClass::getName).orElse("(deleted class)"));
        return m;
    }

    private Map<String, Object> examSummary(Exam e) {
        ExamStatus eff = e.getEffectiveStatus(s.exams().now());
        return Json.obj("id", e.getExamId(), "title", e.getTitle(), "subjectId", e.getSubjectId(),
                "subjectCode", s.subjects().subjectLabel(e.getSubjectId()), "teacherName", s.users().nameOf(e.getTeacherId()),
                "status", e.getStatus().name(), "effectiveStatus", eff.name(), "statusLabel", eff.getLabel(),
                "questionCount", s.exams().questionCount(e), "totalPoints", s.exams().totalPoints(e),
                "opensAt", e.getOpensAt() == null ? null : LOCAL.format(e.getOpensAt()),
                "closesAt", e.getClosesAt() == null ? null : LOCAL.format(e.getClosesAt()),
                "opensLabel", TimeFormatter.dateTime(e.getOpensAt()), "closesLabel", TimeFormatter.dateTime(e.getClosesAt()),
                "timerLabel", e.getTimer().describe(), "maxAttempts", e.getMaxAttempts(),
                "assignment", s.exams().assignmentSummary(e));
    }

    private Map<String, Object> resultRow(ReportService.ResultRow row) {
        Map<String, Object> m = resultBrief(row.result());
        m.put("studentName", row.studentName());
        m.put("studentNumber", row.studentNumber());
        m.put("examTitle", row.examTitle());
        m.put("subjectCode", row.subjectCode());
        return m;
    }

    private Map<String, Object> resultBrief(Result r) {
        return Json.obj("id", r.getResultId(), "sessionId", r.getSessionId(), "examId", r.getExamId(),
                "examTitle", s.store().findExam(r.getExamId()).map(Exam::getTitle).orElse("(deleted)"),
                "studentId", r.getStudentId(), "studentName", s.users().nameOf(r.getStudentId()),
                "attempt", r.getAttemptNumber(), "score", r.getTotalScore(), "maxScore", r.getMaxScore(),
                "percentage", r.getPercentage(), "passed", r.isPassed(), "status", r.getStatus().name(),
                "statusLabel", r.getStatus().getLabel(), "final", r.isFinal(), "released", r.isReleased(),
                "pendingCount", r.getPendingQuestionIds().size());
    }

    private Map<String, Object> attemptRow(ExamSession ses, Result r) {
        Map<String, Object> m = Json.obj("sessionId", ses.getSessionId(), "examId", ses.getExamId(),
                "studentId", ses.getStudentId(), "studentName", s.users().nameOf(ses.getStudentId()),
                "attempt", ses.getAttemptNumber(), "state", ses.getState().name(), "stateLabel", ses.getState().getLabel(),
                "startedAt", TimeFormatter.stamp(ses.getStartedAt(), s.clock().getZone()),
                "submittedAt", ses.getSubmittedAt() == null ? null : TimeFormatter.stamp(ses.getSubmittedAt(), s.clock().getZone()),
                "submissionType", ses.getSubmissionType() == null ? null : ses.getSubmissionType().getLabel(),
                "answered", ses.countAnswered());
        if (r != null) {
            m.put("resultId", r.getResultId());
            m.put("score", r.getTotalScore());
            m.put("maxScore", r.getMaxScore());
            m.put("percentage", r.getPercentage());
            m.put("passed", r.isPassed());
            m.put("final", r.isFinal());
            m.put("released", r.isReleased());
            m.put("statusLabel", r.getStatus().getLabel());
        }
        return m;
    }

    /** Per-question breakdown of a result. The answer key is only included for teachers/admins. */
    private List<Object> resultItems(Result r, Exam e, ExamSession ses, boolean staff) {
        List<Object> items = new ArrayList<>();
        List<Question> order = ses == null ? e.getQuestions() : s.sessions().questionsInOrder(ses, e);
        int n = 1;
        for (Question q : order) {
            QuestionScore qs = r.getScore(q.getQuestionId());
            models.StudentAnswer a = ses == null ? null : ses.getAnswer(q.getQuestionId());
            Map<String, Object> m = Json.obj("number", n++, "questionId", q.getQuestionId(), "type", q.getType().name(),
                    "typeLabel", q.getType().getLabel(), "text", q.getText(),
                    "answer", a == null || !a.isAnswered() ? "(no answer)"
                            : q.formatAnswer(a.getValues(), ses.getChoiceOrder(q.getQuestionId())),
                    "earned", qs == null ? 0.0 : qs.getEarned(), "max", qs == null ? q.getPoints() : qs.getMaxPoints(),
                    "pending", qs != null && qs.isPendingManual(), "feedback", qs == null ? "" : qs.getFeedback(),
                    "explanation", q.getExplanation());
            if (staff) {
                m.put("answerKey", q.describeAnswerKey());
                m.put("manual", q.requiresManualGrading());
                if (q instanceof models.EssayQuestion es) {
                    m.put("rubric", es.getRubric());
                }
            }
            items.add(m);
        }
        return items;
    }

    private Long remainingMs(ExamSession ses) {
        Duration d = ses.remaining(s.sessions().now());
        return d == null ? null : Math.max(0, d.toMillis());
    }

    private Map<String, Object> sessionView(ExamSession ses) throws QuizSystemException {
        Exam e = s.exams().getExam(ses.getExamId());
        Map<String, Object> m = Json.obj("sessionId", ses.getSessionId(), "examId", e.getExamId(),
                "examTitle", e.getTitle(), "instructions", e.getInstructions(),
                "subjectCode", s.subjects().subjectLabel(e.getSubjectId()),
                "attempt", ses.getAttemptNumber(), "maxAttempts", e.getMaxAttempts(),
                "state", ses.getState().name(), "stateLabel", ses.getState().getLabel(),
                "open", ses.isOpen(), "remainingMs", remainingMs(ses),
                "allowPause", e.getTimer().isAllowPause(), "autoSubmit", e.getTimer().isAutoSubmit(),
                "timerLabel", e.getTimer().describe(), "currentIndex", ses.getCurrentIndex(),
                "totalPoints", e.getTotalPoints());
        if (!ses.isOpen()) {
            m.put("ending", s.sessions().describeEnding(ses));
            s.grading().findResultForSession(ses.getSessionId()).ifPresent(r -> m.put("resultId", r.getResultId()));
            return m;
        }
        List<Object> qs = new ArrayList<>();
        int n = 1;
        for (Question q : s.sessions().questionsInOrder(ses, e)) {
            qs.add(QuestionCodec.forStudent(q, ses.getChoiceOrder(q.getQuestionId()), ses.getAnswer(q.getQuestionId()), n++));
        }
        m.put("questions", qs);
        return m;
    }

    private static LocalDateTime localTime(String text, String label) throws ValidationException {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(text.trim().replace(' ', 'T').substring(0, Math.min(16, text.trim().length())), LOCAL);
        } catch (DateTimeParseException e) {
            throw new ValidationException(label + " is not a valid date and time.");
        }
    }
}
