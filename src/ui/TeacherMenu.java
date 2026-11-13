package ui;

import exceptions.QuizSystemException;
import models.Difficulty;
import models.EssayQuestion;
import models.Exam;
import models.ExamSession;
import models.ExamStatus;
import models.Question;
import models.QuestionScore;
import models.QuestionType;
import models.Result;
import models.SchoolClass;
import models.Student;
import models.StudentAnswer;
import models.Subject;
import models.Teacher;
import models.TimerSettings;
import models.User;
import services.ExamService;
import services.QuestionBankService;
import services.ServiceRegistry;
import util.TextUtil;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** Teacher dashboard: subjects, question bank, exams, grading and reports. */
public class TeacherMenu extends BaseMenu {
    private final QuestionForm form;
    private final ReportScreens reports;
    private Teacher teacher;

    public TeacherMenu(ServiceRegistry services, InputReader in) {
        super(services, in);
        this.form = new QuestionForm(services, in);
        this.reports = new ReportScreens(services, in);
    }

    @Override
    public void show(User user) {
        teacher = (Teacher) user;
        runMenu("TEACHER MENU - " + teacher.getFullName(), "Logout",
                item("My subjects and students", this::subjectsMenu),
                item("Question bank", this::questionMenu),
                item("Examinations", this::examMenu),
                item("Submissions and grading", this::gradingMenu),
                item("Results and reports", () -> reports.menu(teacher)),
                item("Change my password", () -> changePassword(teacher)));
    }

    // ================================================================== subjects

    private void subjectsMenu() {
        runMenu("MY SUBJECTS", "Back",
                item("List my subjects", () -> {
                    List<String[]> rows = new ArrayList<>();
                    for (Subject s : mySubjects()) {
                        rows.add(new String[]{s.getCode(), s.getName(), String.valueOf(s.getEnrolledStudentIds().size()),
                                s.getDescription()});
                    }
                    TablePrinter.print(out, new String[]{"Code", "Name", "Enrolled", "Description"}, rows);
                }),
                item("View enrolled students (sorted by name)", () -> {
                    Subject s = pickMySubject();
                    if (s != null) {
                        printUsers(services.subjects().enrolledStudents(s.getSubjectId()));
                    }
                }),
                item("Enrol a student in my subject", () -> {
                    Subject s = pickMySubject();
                    if (s == null) {
                        return;
                    }
                    Student st = choose("Choose a student", services.users().listStudents(), this::userLabel);
                    if (st != null) {
                        boolean added = services.subjects().enrollStudent(teacher, s.getSubjectId(), st.getUserId());
                        success(added ? st.getFullName() + " enrolled in " + s.getCode() + "." : "Already enrolled.");
                    }
                }),
                item("Remove a student from my subject", () -> {
                    Subject s = pickMySubject();
                    if (s == null) {
                        return;
                    }
                    Student st = choose("Choose a student", services.subjects().enrolledStudents(s.getSubjectId()), this::userLabel);
                    if (st != null && confirm("Remove " + st.getFullName() + " from " + s.getCode() + "?")) {
                        services.subjects().unenrollStudent(teacher, s.getSubjectId(), st.getUserId());
                        success("Student removed from the subject.");
                    }
                }));
    }

    private List<Subject> mySubjects() {
        return services.subjects().subjectsForTeacher(teacher.getUserId());
    }

    private Subject pickMySubject() {
        List<Subject> subjects = mySubjects();
        if (subjects.isEmpty()) {
            info("No subjects are assigned to you yet. Ask an administrator to assign one.");
            return null;
        }
        return subjects.size() == 1 ? subjects.get(0) : choose("Choose a subject", subjects, this::subjectLabel);
    }

    // ================================================================== question bank

    private void questionMenu() {
        runMenu("QUESTION BANK", "Back",
                item("List my questions", () -> printQuestions(services.questions().visibleQuestions(teacher))),
                item("Filter by subject / type / difficulty", this::filterQuestions),
                item("Search questions by keyword", () -> printQuestions(
                        services.questions().search(teacher, in.line("Keyword: ")))),
                item("Search questions by category", () -> {
                    List<String> cats = services.questions().categories(teacher);
                    if (!cats.isEmpty()) {
                        info("Existing categories: " + String.join(", ", cats));
                    }
                    printQuestions(services.questions().searchByCategory(teacher, in.line("Category: ")));
                }),
                item("View question details and answer key", () -> {
                    Question q = pickQuestion();
                    if (q != null) {
                        printQuestionDetails(q);
                    }
                }),
                item("Add a question", this::addQuestion),
                item("Edit a question", this::editQuestion),
                item("Delete a question", this::deleteQuestion));
    }

    private void filterQuestions() throws QuizSystemException {
        String subjectId = null;
        if (in.yesNo("Filter by subject?")) {
            Subject s = pickMySubject();
            subjectId = s == null ? null : s.getSubjectId();
        }
        QuestionType type = in.yesNo("Filter by type?") ? form.chooseType() : null;
        Difficulty diff = in.yesNo("Filter by difficulty?") ? form.chooseDifficulty(Difficulty.MEDIUM) : null;
        boolean byDifficulty = in.yesNo("Sort by difficulty (otherwise by subject/type)?");
        printQuestions(services.questions().filter(teacher, subjectId, type, diff,
                byDifficulty ? QuestionBankService.BY_DIFFICULTY : null));
    }

    private Question pickQuestion() throws QuizSystemException {
        return choose("Choose a question", services.questions().visibleQuestions(teacher), this::questionLabel);
    }

    private void printQuestionDetails(Question q) {
        sub(q.getQuestionId() + " - " + q.getType().getLabel());
        out.println("Subject    : " + services.subjects().subjectLabel(q.getSubjectId()));
        out.println("Points     : " + TextUtil.num(q.getPoints()) + "   Difficulty: " + q.getDifficulty().getLabel()
                + "   Category: " + q.getCategory());
        out.println("Grading    : " + (q.requiresManualGrading() ? "manual" : "automatic"));
        out.println(q.render(null));
        out.println("Answer key : " + q.describeAnswerKey());
        if (!q.getExplanation().isEmpty()) {
            out.println("Explanation: " + q.getExplanation());
        }
    }

    private void addQuestion() throws QuizSystemException {
        Subject s = pickMySubject();
        if (s == null) {
            return;
        }
        QuestionType type = form.chooseType();
        if (type == null) {
            return;
        }
        String id = services.questions().nextQuestionId();
        Question q = form.build(type, id, teacher.getUserId(), s.getSubjectId(), null);
        services.questions().addQuestion(teacher, q);
        printQuestionDetails(q);
        success("Question " + q.getQuestionId() + " saved to the question bank.");
    }

    private void editQuestion() throws QuizSystemException {
        Question original = pickQuestion();
        if (original == null) {
            return;
        }
        printQuestionDetails(original);
        out.println("1. Edit text, points, category, difficulty and explanation");
        out.println("2. Re-enter the whole question (choices / answer key), keeping the same ID");
        out.println("0. Cancel");
        int c = in.integer("Choose: ", 0, 2);
        Question updated;
        if (c == 0) {
            return;
        } else if (c == 1) {
            updated = services.questions().getEditableCopy(teacher, original.getQuestionId());
            form.editCommonFields(updated);
        } else {
            info("Current answer key: " + original.describeAnswerKey());
            QuestionType type = in.yesNoOrKeep("Keep the type '" + original.getType().getLabel() + "'?", true)
                    ? original.getType() : form.chooseType();
            if (type == null) {
                return;
            }
            updated = form.build(type, original.getQuestionId(), teacher.getUserId(), original.getSubjectId(), original);
        }
        services.questions().updateQuestion(teacher, updated);
        success("Question updated. Draft exams use the new version; published exams keep the version students saw.");
    }

    private void deleteQuestion() throws QuizSystemException {
        Question q = pickQuestion();
        if (q != null && confirm("Delete " + q.getQuestionId() + " permanently?")) {
            int drafts = services.questions().deleteQuestion(teacher, q.getQuestionId());
            success("Question deleted" + (drafts > 0 ? " and removed from " + drafts + " draft exam(s)." : "."));
        }
    }

    // ================================================================== exams

    private void examMenu() {
        runMenu("EXAMINATIONS", "Back",
                item("List my exams (sorted by date)", () -> printExams(services.exams().examsFor(teacher, ExamService.BY_DATE))),
                item("List my exams (sorted by title)", () -> printExams(services.exams().examsFor(teacher, ExamService.BY_TITLE))),
                item("Search exams by title", () -> printExams(services.exams().searchByTitle(teacher, in.line("Title contains: ")))),
                item("View exam details", () -> {
                    Exam e = pickExam(null);
                    if (e != null) {
                        sub("Exam details");
                        printExamDetails(e);
                        out.println("  Assigned to    : " + services.exams().assignmentSummary(e));
                    }
                }),
                item("Create a new exam", this::createExam),
                item("Edit exam settings (draft only)", this::editExam),
                item("Manage exam questions (draft only)", this::manageExamQuestions),
                item("Assign exam to students / classes", this::assignExam),
                item("Set or change schedule", () -> {
                    Exam e = pickExam(null);
                    if (e != null) {
                        editSchedule(e);
                    }
                }),
                item("Preview exam", this::previewExam),
                item("Publish exam", () -> lifecycle("publish")),
                item("Return published exam to draft", () -> lifecycle("unpublish")),
                item("Close exam now", () -> lifecycle("close")),
                item("Archive exam", () -> lifecycle("archive")),
                item("Delete exam", () -> lifecycle("delete")));
    }

    private Exam pickExam(ExamStatus onlyStatus) throws QuizSystemException {
        List<Exam> exams = services.exams().examsFor(teacher, ExamService.BY_DATE).stream()
                .filter(e -> onlyStatus == null || e.getStatus() == onlyStatus).collect(Collectors.toList());
        return choose("Choose an exam", exams, this::examLabel);
    }

    private void createExam() throws QuizSystemException {
        Subject s = pickMySubject();
        if (s == null) {
            return;
        }
        String title = in.required("Exam title: ");
        String instructions = in.line("Instructions for students (optional): ");
        Exam e = services.exams().createExam(teacher, s.getSubjectId(), title, instructions);
        success("Draft exam " + e.getExamId() + " created. Now configure it.");
        editDetails(e);
        editTimer(e);
        editSchedule(e);
        if (in.yesNo("Add questions from the question bank now?")) {
            addQuestionsToExam(e);
        }
        info("Next steps: assign the exam to students or classes, preview it, then publish it.");
    }

    private void editExam() throws QuizSystemException {
        Exam e = pickExam(ExamStatus.DRAFT);
        if (e == null) {
            return;
        }
        out.println("1. General settings  2. Timer  3. Schedule  4. All of them");
        int c = in.integer("Choose: ", 1, 4);
        if (c == 1 || c == 4) {
            editDetails(e);
        }
        if (c == 2 || c == 4) {
            editTimer(e);
        }
        if (c == 3 || c == 4) {
            editSchedule(e);
        }
    }

    private void editDetails(Exam e) throws QuizSystemException {
        sub("General settings (Enter keeps the current value)");
        String title = in.optional("Title", e.getTitle());
        String instructions = in.optional("Instructions", e.getInstructions());
        double passing = in.decimalOrKeep("Passing score (%)", e.getPassingPercentage(), 0, 100);
        int attempts = in.integerOrKeep("Maximum attempts", e.getMaxAttempts(), 1, Exam.MAX_ATTEMPTS_LIMIT);
        boolean shuffleQ = in.yesNoOrKeep("Randomise question order?", e.isShuffleQuestions());
        boolean shuffleC = in.yesNoOrKeep("Randomise answer choices?", e.isShuffleChoices());
        boolean autoRelease = in.yesNoOrKeep("Release final results to students automatically?", e.isAutoReleaseResults());
        services.exams().updateDetails(teacher, e.getExamId(), title, instructions, passing, attempts, shuffleQ, shuffleC, autoRelease);
        success("Settings saved.");
    }

    private void editTimer(Exam e) throws QuizSystemException {
        sub("Timer (current: " + e.getTimer().describe() + ")");
        boolean enabled = in.yesNoOrKeep("Enable the examination timer?", e.getTimer().isEnabled());
        Duration duration = null;
        boolean pause = false;
        if (enabled) {
            String[] labels = {"5 minutes", "10 minutes", "15 minutes", "30 minutes", "45 minutes", "1 hour",
                    "1 hour 30 minutes", "2 hours", "Custom duration"};
            long[] minutes = {5, 10, 15, 30, 45, 60, 90, 120};
            for (int i = 0; i < labels.length; i++) {
                out.printf("  %d. %s%n", i + 1, labels[i]);
            }
            int c = in.integer("Duration: ", 1, labels.length);
            duration = c <= minutes.length ? Duration.ofMinutes(minutes[c - 1])
                    : in.duration("Custom duration (e.g. 01:15:30, 1h15m, 50 = minutes): ");
            pause = in.yesNoOrKeep("Allow students to pause the timer?", e.getTimer().isAllowPause());
        }
        boolean autoSubmit = in.yesNoOrKeep("Submit automatically when time expires?", e.getTimer().isAutoSubmit());
        services.exams().setTimer(teacher, e.getExamId(), new TimerSettings(enabled, duration, autoSubmit, pause));
        success("Timer saved: " + e.getTimer().describe());
    }

    private void editSchedule(Exam e) throws QuizSystemException {
        sub("Schedule (students can start only between these times)");
        LocalDateTime opens = in.dateTimeOrKeep("Opens", e.getOpensAt());
        LocalDateTime closes = in.dateTimeOrKeep("Closes", e.getClosesAt());
        services.exams().setSchedule(teacher, e.getExamId(), opens, closes);
        success("Schedule saved: opens " + date(opens) + ", closes " + date(closes) + ".");
    }

    private void manageExamQuestions() throws QuizSystemException {
        Exam e = pickExam(ExamStatus.DRAFT);
        if (e == null) {
            return;
        }
        while (true) {
            sub("Questions in " + e.getTitle() + " (" + services.exams().questionCount(e) + " items, "
                    + TextUtil.num(services.exams().totalPoints(e)) + " points)");
            printQuestions(services.exams().questionsForPreview(teacher, e));
            out.println("1. Add questions from the bank  2. Remove a question  0. Done");
            int c = in.integer("Choose: ", 0, 2);
            if (c == 0) {
                return;
            }
            execute(() -> {
                if (c == 1) {
                    addQuestionsToExam(e);
                } else {
                    Question q = choose("Remove which question?", services.exams().questionsForPreview(teacher, e), this::questionLabel);
                    if (q != null) {
                        services.exams().removeQuestion(teacher, e.getExamId(), q.getQuestionId());
                        success("Removed " + q.getQuestionId() + ".");
                    }
                }
            });
        }
    }

    private void addQuestionsToExam(Exam e) throws QuizSystemException {
        List<Question> candidates = services.questions().filter(teacher, e.getSubjectId(), null, null, null).stream()
                .filter(q -> !e.getDraftQuestionIds().contains(q.getQuestionId())).collect(Collectors.toList());
        if (candidates.isEmpty()) {
            info("No more questions for this subject in your bank. Add questions in the Question bank menu first.");
            return;
        }
        List<Question> picked = chooseMany("Questions available for this subject", candidates, this::questionLabel);
        for (Question q : picked) {
            services.exams().addQuestion(teacher, e.getExamId(), q.getQuestionId());
        }
        if (!picked.isEmpty()) {
            success(picked.size() + " question(s) added. Total points: " + TextUtil.num(services.exams().totalPoints(e)));
        }
    }

    private void assignExam() throws QuizSystemException {
        Exam e = pickExam(null);
        if (e == null) {
            return;
        }
        Subject subject = services.subjects().getSubject(e.getSubjectId());
        while (true) {
            sub("Assignment of '" + e.getTitle() + "': " + services.exams().assignmentSummary(e));
            out.println("1. Assign individual students   2. Unassign a student");
            out.println("3. Assign a class               4. Unassign a class");
            out.println("5. Toggle 'all students enrolled in " + subject.getCode() + "' (now: "
                    + TextUtil.yesNo(e.isAssignWholeSubject()) + ")");
            out.println("0. Done");
            int c = in.integer("Choose: ", 0, 5);
            if (c == 0) {
                return;
            }
            execute(() -> {
                switch (c) {
                    case 1 -> {
                        List<Student> list = chooseMany("Students enrolled in " + subject.getCode(),
                                services.subjects().enrolledStudents(subject.getSubjectId()), this::userLabel);
                        int added = 0;
                        for (Student s : list) {
                            if (services.exams().assignStudent(teacher, e.getExamId(), s.getUserId())) {
                                added++;
                            }
                        }
                        success(added + " student(s) assigned.");
                    }
                    case 2 -> {
                        List<Student> assigned = e.getAssignedStudentIds().stream().map(services.store()::findUser)
                                .filter(java.util.Optional::isPresent).map(o -> (Student) o.get()).collect(Collectors.toList());
                        Student s = choose("Unassign which student?", assigned, this::userLabel);
                        if (s != null) {
                            services.exams().unassignStudent(teacher, e.getExamId(), s.getUserId());
                            success("Student unassigned.");
                        }
                    }
                    case 3 -> {
                        SchoolClass cl = choose("Assign which class?", services.subjects().listClasses(), this::classLabel);
                        if (cl != null) {
                            services.exams().assignClass(teacher, e.getExamId(), cl.getClassId());
                            success("Class " + cl.getName() + " assigned.");
                        }
                    }
                    case 4 -> {
                        List<SchoolClass> assigned = e.getAssignedClassIds().stream().map(services.store()::findClass)
                                .filter(java.util.Optional::isPresent).map(java.util.Optional::get).collect(Collectors.toList());
                        SchoolClass cl = choose("Unassign which class?", assigned, this::classLabel);
                        if (cl != null) {
                            services.exams().unassignClass(teacher, e.getExamId(), cl.getClassId());
                            success("Class unassigned.");
                        }
                    }
                    default -> {
                        services.exams().setAssignWholeSubject(teacher, e.getExamId(), !e.isAssignWholeSubject());
                        success("Whole-subject assignment is now " + (e.isAssignWholeSubject() ? "ON" : "OFF") + ".");
                    }
                }
            });
        }
    }

    private void previewExam() throws QuizSystemException {
        Exam e = pickExam(null);
        if (e == null) {
            return;
        }
        header("PREVIEW - " + e.getTitle());
        printExamDetails(e);
        int n = 1;
        for (Question q : services.exams().questionsForPreview(teacher, e)) {
            out.println();
            out.println("Q" + n++ + ". (" + TextUtil.num(q.getPoints()) + " pt, " + q.getType().getLabel() + ")");
            out.println(q.render(null));
            out.println("   >> Key: " + q.describeAnswerKey());
        }
        in.pause();
    }

    private void lifecycle(String action) throws QuizSystemException {
        Exam e = pickExam(null);
        if (e == null) {
            return;
        }
        String id = e.getExamId();
        switch (action) {
            case "publish" -> {
                if (confirm("Publish '" + e.getTitle() + "'? Its questions will be locked for students.")) {
                    services.exams().publish(teacher, id);
                    success("Published. Assigned students can take it " + (e.getOpensAt() == null ? "now."
                            : "from " + date(e.getOpensAt()) + "."));
                }
            }
            case "unpublish" -> {
                services.exams().unpublish(teacher, id);
                success("The exam is a draft again and hidden from students.");
            }
            case "close" -> {
                if (confirm("Close '" + e.getTitle() + "' now? Attempts in progress will be finished.")) {
                    services.exams().close(teacher, id);
                    int finished = services.sessions().processExpiredSessions();
                    success("Exam closed." + (finished > 0 ? " " + finished + " open attempt(s) were finished." : ""));
                }
            }
            case "archive" -> {
                if (confirm("Archive '" + e.getTitle() + "'? It will be hidden from students but results are kept.")) {
                    services.exams().archive(teacher, id);
                    services.sessions().processExpiredSessions();
                    success("Exam archived.");
                }
            }
            default -> {
                if (confirm("Delete '" + e.getTitle() + "' permanently?")) {
                    services.exams().delete(teacher, id);
                    success("Exam deleted.");
                }
            }
        }
    }

    // ================================================================== grading

    private void gradingMenu() {
        runMenu("SUBMISSIONS AND GRADING", "Back",
                item("View submissions for an exam", this::viewSubmissions),
                item("View one student's answers", this::viewAnswers),
                item("Grade answers awaiting manual grading", this::gradePending),
                item("Regrade a question / add feedback", this::regrade),
                item("Add overall feedback to a result", this::overallFeedback),
                item("Release final results of an exam", () -> {
                    Exam e = pickExam(null);
                    if (e != null) {
                        int[] r = services.grading().releaseResults(teacher, e.getExamId());
                        success(r[0] + " result(s) released." + (r[1] > 0 ? " " + r[1]
                                + " result(s) still need manual grading and were NOT released." : ""));
                    }
                }),
                item("Withdraw a released result", () -> {
                    Result r = pickResult(true);
                    if (r != null) {
                        services.grading().withdrawRelease(teacher, r.getResultId());
                        success("Result hidden from the student again.");
                    }
                }));
    }

    private void viewSubmissions() throws QuizSystemException {
        Exam e = pickExam(null);
        if (e == null) {
            return;
        }
        List<String[]> rows = new ArrayList<>();
        for (ExamSession s : services.sessions().sessionsForExam(teacher, e.getExamId())) {
            Result r = services.grading().findResultForSession(s.getSessionId()).orElse(null);
            rows.add(new String[]{services.users().nameOf(s.getStudentId()), String.valueOf(s.getAttemptNumber()),
                    stamp(s.getStartedAt()), stamp(s.getSubmittedAt() != null ? s.getSubmittedAt() : s.getExpiredAt()),
                    s.getSubmissionType() == null ? "-" : s.getSubmissionType().getLabel(), s.getState().getLabel(),
                    r == null ? "-" : TextUtil.num(r.getTotalScore()) + "/" + TextUtil.num(r.getMaxScore()),
                    r == null ? "-" : r.getStatus().getLabel() + (r.isReleased() ? ", released" : "")});
        }
        header("SUBMISSIONS - " + e.getTitle());
        TablePrinter.print(out, new String[]{"Student", "Try", "Started", "Ended", "Submission", "State", "Score", "Result"}, rows);
    }

    private Result pickResult(boolean releasedOnly) throws QuizSystemException {
        Exam e = pickExam(null);
        if (e == null) {
            return null;
        }
        List<Result> results = services.grading().resultsForExam(teacher, e.getExamId()).stream()
                .filter(r -> !releasedOnly || r.isReleased()).collect(Collectors.toList());
        return choose("Choose a result", results, this::resultLabel);
    }

    private String resultLabel(Result r) {
        return services.users().nameOf(r.getStudentId()) + " - attempt " + r.getAttemptNumber() + " - "
                + TextUtil.num(r.getTotalScore()) + "/" + TextUtil.num(r.getMaxScore()) + " (" + TextUtil.pct(r.getPercentage())
                + ") " + r.getStatus().getLabel() + (r.isReleased() ? ", released" : "");
    }

    private void viewAnswers() throws QuizSystemException {
        Result r = pickResult(false);
        if (r != null) {
            printSubmission(r, false);
            in.pause();
        }
    }

    /** Prints each question with the student's answer and score; returns the exam. */
    private Exam printSubmission(Result r, boolean pendingOnly) throws QuizSystemException {
        Exam e = services.exams().getExam(r.getExamId());
        ExamSession s = services.sessions().findSession(r.getSessionId()).orElseThrow();
        header(services.users().nameOf(r.getStudentId()) + " - " + e.getTitle() + " (attempt " + r.getAttemptNumber() + ")");
        int n = 1;
        for (Question q : e.getQuestions()) {
            QuestionScore qs = r.getScore(q.getQuestionId());
            if (pendingOnly && (qs == null || !qs.isPendingManual())) {
                n++;
                continue;
            }
            printAnswer(n++, q, s, qs);
        }
        out.println();
        out.println("TOTAL: " + TextUtil.num(r.getTotalScore()) + " / " + TextUtil.num(r.getMaxScore()) + " ("
                + TextUtil.pct(r.getPercentage()) + ") - " + r.getStatus().getLabel()
                + (r.isFinal() ? (r.isPassed() ? ", PASSED" : ", FAILED") : ""));
        return e;
    }

    private void printAnswer(int number, Question q, ExamSession s, QuestionScore qs) {
        StudentAnswer a = s.getAnswer(q.getQuestionId());
        out.println();
        out.println("Q" + number + ". [" + q.getQuestionId() + ", " + q.getType().getLabel() + "] " + q.getText());
        String answer = a == null || !a.isAnswered() ? "(not answered)"
                : q.formatAnswer(a.getValues(), s.getChoiceOrder(q.getQuestionId()));
        out.println("   Student answer: " + answer);
        if (q instanceof EssayQuestion && a != null && a.isAnswered()) {
            out.println("   Word count    : " + EssayQuestion.wordCount(answer));
        }
        out.println("   Answer key    : " + q.describeAnswerKey());
        if (qs != null) {
            out.println("   Score         : " + (qs.isPendingManual() ? "AWAITING MANUAL GRADING" : TextUtil.num(qs.getEarned()))
                    + " / " + TextUtil.num(qs.getMaxPoints()) + (qs.getFeedback().isEmpty() ? "" : "   Feedback: " + qs.getFeedback()));
        }
    }

    private void gradePending() throws QuizSystemException {
        List<Result> pending = services.grading().pendingResults(teacher);
        Result r = choose("Results awaiting manual grading", pending, this::resultLabel);
        if (r == null) {
            return;
        }
        Exam e = printSubmission(r, true);
        for (String qid : r.getPendingQuestionIds()) {
            QuestionScore qs = r.getScore(qid);
            Question q = e.findQuestion(qid);
            sub("Grade " + qid + " (" + (q == null ? "?" : q.getType().getLabel()) + ")");
            double points = in.decimal("Points (0 - " + TextUtil.num(qs.getMaxPoints()) + "): ", 0, qs.getMaxPoints());
            String feedback = in.line("Feedback for the student (optional): ");
            services.grading().gradeQuestion(teacher, r.getResultId(), qid, points, feedback);
            success("Saved.");
        }
        r = services.grading().getResult(r.getResultId());
        success("Result is now " + r.getStatus().getLabel() + ": " + TextUtil.num(r.getTotalScore()) + "/"
                + TextUtil.num(r.getMaxScore()) + " (" + TextUtil.pct(r.getPercentage()) + ")"
                + (r.isReleased() ? ", released to the student." : ". Use 'Release final results' when ready."));
    }

    private void regrade() throws QuizSystemException {
        Result r = pickResult(false);
        if (r == null) {
            return;
        }
        Exam e = printSubmission(r, false);
        Question q = choose("Which question?", e.getQuestions(), this::questionLabel);
        if (q == null) {
            return;
        }
        QuestionScore qs = r.getScore(q.getQuestionId());
        double points = in.decimal("New points (0 - " + TextUtil.num(qs.getMaxPoints()) + "): ", 0, qs.getMaxPoints());
        String feedback = in.optional("Feedback", qs.getFeedback());
        Result updated = services.grading().gradeQuestion(teacher, r.getResultId(), q.getQuestionId(), points, feedback);
        success("Saved. New total: " + TextUtil.num(updated.getTotalScore()) + "/" + TextUtil.num(updated.getMaxScore())
                + " (" + updated.getStatus().getLabel() + ")");
    }

    private void overallFeedback() throws QuizSystemException {
        Result r = pickResult(false);
        if (r != null) {
            String fb = in.optional("Overall feedback", r.getOverallFeedback());
            services.grading().setOverallFeedback(teacher, r.getResultId(), fb);
            success("Feedback saved.");
        }
    }
}
