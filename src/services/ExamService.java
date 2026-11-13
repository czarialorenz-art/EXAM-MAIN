package services;

import data.DataStore;
import exceptions.AccessDeniedException;
import exceptions.InvalidExamException;
import exceptions.QuizSystemException;
import exceptions.ValidationException;
import models.Exam;
import models.ExamStatus;
import models.Question;
import models.Role;
import models.SchoolClass;
import models.Subject;
import models.Teacher;
import models.TimerSettings;
import models.User;
import util.TextUtil;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Exam creation, configuration, assignment and lifecycle (draft -> published -> closed -> archived). */
public class ExamService {
    public static final Comparator<Exam> BY_DATE = Comparator
            .comparing((Exam e) -> e.getOpensAt() == null ? e.getCreatedAt() : e.getOpensAt())
            .thenComparing(Exam::getTitle, String.CASE_INSENSITIVE_ORDER);
    public static final Comparator<Exam> BY_TITLE = Comparator.comparing(Exam::getTitle, String.CASE_INSENSITIVE_ORDER);

    private final DataStore store;
    private final Clock clock;

    public ExamService(DataStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    public LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    public Exam createExam(User actor, String subjectId, String title, String instructions) throws QuizSystemException {
        Teacher t = AccessControl.requireTeacher(actor);
        Subject s = store.findSubject(subjectId).orElseThrow(() -> new InvalidExamException("Subject not found."));
        if (!t.getUserId().equals(s.getTeacherId())) {
            throw new AccessDeniedException("You are not assigned to teach " + s.getCode() + ".");
        }
        Exam.checkTitle(title);
        Exam e = new Exam(store.nextId("EXM"), t.getUserId(), subjectId, title, instructions, now());
        store.addExam(e);
        store.saveExams();
        return e;
    }

    /** Updates the general settings of a draft. Everything is validated before anything changes. */
    public void updateDetails(User actor, String examId, String title, String instructions, double passingPercentage,
                              int maxAttempts, boolean shuffleQuestions, boolean shuffleChoices,
                              boolean autoReleaseResults) throws QuizSystemException {
        Exam e = requireOwnedDraft(actor, examId);
        Exam.checkTitle(title);
        Exam.checkPassing(passingPercentage);
        Exam.checkAttempts(maxAttempts);
        e.setTitle(title);
        e.setInstructions(instructions);
        e.setPassingPercentage(passingPercentage);
        e.setMaxAttempts(maxAttempts);
        e.setShuffleQuestions(shuffleQuestions);
        e.setShuffleChoices(shuffleChoices);
        e.setAutoReleaseResults(autoReleaseResults);
        store.saveExams();
    }

    public void setTimer(User actor, String examId, TimerSettings timer) throws QuizSystemException {
        Exam e = requireOwnedDraft(actor, examId);
        e.setTimer(timer);
        store.saveExams();
    }

    /**
     * Sets the schedule (null = no limit). Drafts can change freely; for a published exam that
     * nobody has started yet the schedule may be changed, but the closing time must be in the future.
     */
    public void setSchedule(User actor, String examId, LocalDateTime opensAt, LocalDateTime closesAt)
            throws QuizSystemException {
        Exam e = requireOwned(actor, examId);
        if (e.getStatus() != ExamStatus.DRAFT && e.getStatus() != ExamStatus.PUBLISHED) {
            throw new InvalidExamException("The schedule of a " + e.getStatus().getLabel().toLowerCase() + " exam cannot be changed.");
        }
        if (e.getStatus() == ExamStatus.PUBLISHED && closesAt != null && !closesAt.isAfter(now())) {
            throw new InvalidExamException("The closing time of a published exam must be in the future.");
        }
        e.setSchedule(opensAt, closesAt);
        store.saveExams();
    }

    public void addQuestion(User actor, String examId, String questionId) throws QuizSystemException {
        Exam e = requireOwnedDraft(actor, examId);
        Question q = store.findQuestion(questionId).orElseThrow(() -> new InvalidExamException("Question not found."));
        if (!actor.getUserId().equals(q.getCreatedByTeacherId())) {
            throw new AccessDeniedException("You can only add your own questions.");
        }
        if (!q.getSubjectId().equals(e.getSubjectId())) {
            throw new InvalidExamException("Question " + questionId + " belongs to a different subject.");
        }
        if (!e.addDraftQuestion(questionId)) {
            throw new InvalidExamException("Question " + questionId + " is already in this exam.");
        }
        store.saveExams();
    }

    public void removeQuestion(User actor, String examId, String questionId) throws QuizSystemException {
        Exam e = requireOwnedDraft(actor, examId);
        if (!e.removeDraftQuestion(questionId)) {
            throw new InvalidExamException("Question " + questionId + " is not in this exam.");
        }
        store.saveExams();
    }

    // ------------------------------------------------------------------ assignment

    public boolean assignStudent(User actor, String examId, String studentId) throws QuizSystemException {
        Exam e = requireAssignable(actor, examId);
        Subject s = store.findSubject(e.getSubjectId()).orElseThrow(() -> new InvalidExamException("Subject not found."));
        if (!s.isEnrolled(studentId)) {
            throw new ValidationException("That student is not enrolled in " + s.getCode() + ". Enrol the student first.");
        }
        boolean added = e.assignStudent(studentId);
        store.saveExams();
        return added;
    }

    public boolean unassignStudent(User actor, String examId, String studentId) throws QuizSystemException {
        Exam e = requireAssignable(actor, examId);
        boolean removed = e.unassignStudent(studentId);
        store.saveExams();
        return removed;
    }

    public boolean assignClass(User actor, String examId, String classId) throws QuizSystemException {
        Exam e = requireAssignable(actor, examId);
        store.findClass(classId).orElseThrow(() -> new ValidationException("Class not found."));
        boolean added = e.assignClass(classId);
        store.saveExams();
        return added;
    }

    public boolean unassignClass(User actor, String examId, String classId) throws QuizSystemException {
        Exam e = requireAssignable(actor, examId);
        boolean removed = e.unassignClass(classId);
        store.saveExams();
        return removed;
    }

    public void setAssignWholeSubject(User actor, String examId, boolean value) throws QuizSystemException {
        Exam e = requireAssignable(actor, examId);
        e.setAssignWholeSubject(value);
        store.saveExams();
    }

    /** True if the student is assigned directly, through a class, or through subject enrolment. */
    public boolean isAssigned(Exam e, String studentId) {
        if (e.getAssignedStudentIds().contains(studentId)) {
            return true;
        }
        for (String classId : e.getAssignedClassIds()) {
            if (store.findClass(classId).map(c -> c.hasStudent(studentId)).orElse(false)) {
                return true;
            }
        }
        return e.isAssignWholeSubject()
                && store.findSubject(e.getSubjectId()).map(s -> s.isEnrolled(studentId)).orElse(false);
    }

    /** Every student the exam is assigned to (directly, by class or by subject). */
    public Set<String> assignedStudentIds(Exam e) {
        Set<String> ids = new LinkedHashSet<>(e.getAssignedStudentIds());
        for (String classId : e.getAssignedClassIds()) {
            store.findClass(classId).ifPresent(c -> ids.addAll(c.getStudentIds()));
        }
        if (e.isAssignWholeSubject()) {
            store.findSubject(e.getSubjectId()).ifPresent(s -> ids.addAll(s.getEnrolledStudentIds()));
        }
        return ids;
    }

    // ------------------------------------------------------------------ lifecycle

    /** Validates the exam, snapshots its questions and makes it visible to assigned students. */
    public void publish(User actor, String examId) throws QuizSystemException {
        Exam e = requireOwnedDraft(actor, examId);
        if (e.getDraftQuestionIds().isEmpty()) {
            throw new InvalidExamException("Add at least one question before publishing.");
        }
        e.getTimer().validate();
        if (!e.isAssignWholeSubject() && e.getAssignedStudentIds().isEmpty() && e.getAssignedClassIds().isEmpty()) {
            throw new InvalidExamException("Assign the exam to at least one student, class, or the whole subject before publishing.");
        }
        if (e.getClosesAt() != null && !e.getClosesAt().isAfter(now())) {
            throw new InvalidExamException("The closing date/time is already in the past. Update the schedule first.");
        }
        List<Question> snapshot = new ArrayList<>();
        for (String qid : e.getDraftQuestionIds()) {
            Question q = store.findQuestion(qid).orElseThrow(
                    () -> new InvalidExamException("Question " + qid + " no longer exists. Remove it from the exam."));
            q.validate();
            requireClassAssigned(e, q);
            snapshot.add(q.copy());
        }
        e.markPublished(snapshot, now());
        store.saveExams();
    }

    /**
     * A question written for one class may only be published in an exam that is assigned to
     * that class, so it is seen by the class it belongs to.
     */
    private void requireClassAssigned(Exam e, Question q) throws InvalidExamException {
        String classId = q.getClassId();
        if (classId == null || e.getAssignedClassIds().contains(classId)) {
            return;
        }
        String name = store.findClass(classId).map(SchoolClass::getName).orElse(classId);
        throw new InvalidExamException("Question " + q.getQuestionId() + " belongs to class " + name
                + ", but this exam is not assigned to that class. Assign the exam to " + name
                + " or remove the question.");
    }

    public void unpublish(User actor, String examId) throws QuizSystemException {
        Exam e = requireOwned(actor, examId);
        if (e.getStatus() != ExamStatus.PUBLISHED) {
            throw new InvalidExamException("Only published exams can be returned to draft.");
        }
        if (hasSessions(examId)) {
            throw new InvalidExamException("Students have already started this exam, so it cannot return to draft.");
        }
        e.markUnpublished();
        store.saveExams();
    }

    /** Closes a published exam now. Open attempts are finalised by ExamSessionService. */
    public void close(User actor, String examId) throws QuizSystemException {
        Exam e = requireOwned(actor, examId);
        if (e.getStatus() != ExamStatus.PUBLISHED) {
            throw new InvalidExamException("Only published exams can be closed.");
        }
        e.markClosed(now());
        store.saveExams();
    }

    public void archive(User actor, String examId) throws QuizSystemException {
        Exam e = requireOwned(actor, examId);
        if (e.getStatus() == ExamStatus.ARCHIVED) {
            throw new InvalidExamException("The exam is already archived.");
        }
        e.markArchived(now());
        store.saveExams();
    }

    public void delete(User actor, String examId) throws QuizSystemException {
        requireOwned(actor, examId);
        if (hasSessions(examId)) {
            throw new InvalidExamException("Students have attempted this exam. Archive it instead of deleting it.");
        }
        store.removeExam(examId);
        store.saveExams();
    }

    private boolean hasSessions(String examId) {
        return store.getSessions().stream().anyMatch(s -> s.getExamId().equals(examId));
    }

    // ------------------------------------------------------------------ queries

    public Exam getExam(String examId) throws InvalidExamException {
        return store.findExam(examId).orElseThrow(() -> new InvalidExamException("Exam not found."));
    }

    /** Teachers see their own exams, administrators see all. */
    public List<Exam> examsFor(User actor, Comparator<Exam> order) throws AccessDeniedException {
        AccessControl.requireRole(actor, Role.TEACHER, Role.ADMIN);
        return store.getExams().stream()
                .filter(e -> actor.getRole() == Role.ADMIN || e.getTeacherId().equals(actor.getUserId()))
                .sorted(order == null ? BY_DATE : order).collect(Collectors.toList());
    }

    public List<Exam> searchByTitle(User actor, String query) throws AccessDeniedException {
        String q = query == null ? "" : query.trim();
        return examsFor(actor, BY_TITLE).stream().filter(e -> TextUtil.containsIgnoreCase(e.getTitle(), q))
                .collect(Collectors.toList());
    }

    /** Published (upcoming, open or closed) exams assigned to the student. */
    public List<Exam> availableExamsFor(User actor) throws AccessDeniedException {
        AccessControl.requireStudent(actor);
        return store.getExams().stream()
                .filter(e -> e.getStatus() == ExamStatus.PUBLISHED || e.getStatus() == ExamStatus.COMPLETED)
                .filter(e -> isAssigned(e, actor.getUserId()))
                .sorted(BY_DATE).collect(Collectors.toList());
    }

    /** Questions in exam order: the bank versions while DRAFT, otherwise the published snapshot. */
    public List<Question> questionsForPreview(User actor, Exam e) throws QuizSystemException {
        requireViewable(actor, e.getExamId());
        if (!e.isDraft()) {
            return e.getQuestions();
        }
        List<Question> list = new ArrayList<>();
        for (String id : e.getDraftQuestionIds()) {
            store.findQuestion(id).ifPresent(list::add);
        }
        return list;
    }

    public double totalPoints(Exam e) {
        if (!e.isDraft()) {
            return e.getTotalPoints();
        }
        return e.getDraftQuestionIds().stream().map(store::findQuestion).filter(java.util.Optional::isPresent)
                .mapToDouble(o -> o.get().getPoints()).sum();
    }

    public int questionCount(Exam e) {
        return e.isDraft() ? e.getDraftQuestionIds().size() : e.getQuestions().size();
    }

    public String assignmentSummary(Exam e) {
        List<String> parts = new ArrayList<>();
        if (e.isAssignWholeSubject()) {
            parts.add("all students enrolled in the subject");
        }
        if (!e.getAssignedClassIds().isEmpty()) {
            parts.add("classes: " + e.getAssignedClassIds().stream()
                    .map(id -> store.findClass(id).map(SchoolClass::getName).orElse(id)).collect(Collectors.joining(", ")));
        }
        if (!e.getAssignedStudentIds().isEmpty()) {
            parts.add(e.getAssignedStudentIds().size() + " individual student(s)");
        }
        return parts.isEmpty() ? "nobody yet" : String.join("; ", parts);
    }

    /** Teacher who owns the exam (for changes). */
    public Exam requireOwned(User actor, String examId) throws QuizSystemException {
        Teacher t = AccessControl.requireTeacher(actor);
        Exam e = getExam(examId);
        if (!e.getTeacherId().equals(t.getUserId())) {
            throw new AccessDeniedException("You can only manage your own exams.");
        }
        return e;
    }

    /** Owner teacher or any administrator (read-only access). */
    public Exam requireViewable(User actor, String examId) throws QuizSystemException {
        AccessControl.requireRole(actor, Role.TEACHER, Role.ADMIN);
        Exam e = getExam(examId);
        if (actor.getRole() == Role.TEACHER && !e.getTeacherId().equals(actor.getUserId())) {
            throw new AccessDeniedException("You can only view your own exams.");
        }
        return e;
    }

    private Exam requireOwnedDraft(User actor, String examId) throws QuizSystemException {
        Exam e = requireOwned(actor, examId);
        if (!e.isDraft()) {
            throw new InvalidExamException("Only draft exams can be edited. Return it to draft first "
                    + "(possible only while no student has started it).");
        }
        return e;
    }

    private Exam requireAssignable(User actor, String examId) throws QuizSystemException {
        Exam e = requireOwned(actor, examId);
        if (e.getStatus() != ExamStatus.DRAFT && e.getStatus() != ExamStatus.PUBLISHED) {
            throw new InvalidExamException("Assignments cannot be changed for a " + e.getStatus().getLabel().toLowerCase() + " exam.");
        }
        return e;
    }
}
