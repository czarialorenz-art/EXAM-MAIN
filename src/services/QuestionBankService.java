package services;

import data.DataStore;
import exceptions.AccessDeniedException;
import exceptions.InvalidQuestionException;
import exceptions.QuizSystemException;
import models.Difficulty;
import models.Exam;
import models.Question;
import models.QuestionType;
import models.Role;
import models.Subject;
import models.Teacher;
import models.User;
import util.TextUtil;

import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Teachers' reusable question bank. */
public class QuestionBankService {
    public static final Comparator<Question> DEFAULT_ORDER = Comparator.comparing(Question::getSubjectId)
            .thenComparing(Question::getType).thenComparing(Question::getQuestionId);
    public static final Comparator<Question> BY_DIFFICULTY = Comparator.comparing(Question::getDifficulty)
            .thenComparing(Question::getQuestionId);

    private final DataStore store;

    public QuestionBankService(DataStore store) {
        this.store = store;
    }

    public String nextQuestionId() throws QuizSystemException {
        return store.nextId("Q");
    }

    /** Validates and saves a new question. The teacher must teach the question's subject. */
    public void addQuestion(User actor, Question q) throws QuizSystemException {
        Teacher t = AccessControl.requireTeacher(actor);
        if (!t.getUserId().equals(q.getCreatedByTeacherId())) {
            throw new AccessDeniedException("Questions must be created under your own account.");
        }
        requireTeachesSubject(t, q.getSubjectId());
        requireClassExists(q.getClassId());
        if (store.findQuestion(q.getQuestionId()).isPresent()) {
            throw new InvalidQuestionException("A question with ID " + q.getQuestionId() + " already exists.");
        }
        q.validate();
        store.addQuestion(q);
        store.saveQuestions();
    }

    /** Returns a deep copy the UI may modify; changes are kept only if passed to {@link #updateQuestion}. */
    public Question getEditableCopy(User actor, String questionId) throws QuizSystemException {
        return requireOwned(actor, questionId).copy();
    }

    /** Replaces a question (same ID) after validation. Published exams keep their own snapshot. */
    public void updateQuestion(User actor, Question updated) throws QuizSystemException {
        Question existing = requireOwned(actor, updated.getQuestionId());
        if (!existing.getCreatedByTeacherId().equals(updated.getCreatedByTeacherId())) {
            throw new AccessDeniedException("The question owner cannot be changed.");
        }
        requireTeachesSubject((Teacher) actor, updated.getSubjectId());
        requireClassExists(updated.getClassId());
        updated.validate();
        store.replaceQuestion(updated);
        store.saveQuestions();
    }

    /** Deletes a question and removes it from draft exams. Returns the number of drafts changed. */
    public int deleteQuestion(User actor, String questionId) throws QuizSystemException {
        requireOwned(actor, questionId);
        int affected = 0;
        for (Exam e : store.getExams()) {
            if (e.isDraft() && e.removeDraftQuestion(questionId)) {
                affected++;
            }
        }
        store.removeQuestion(questionId);
        store.saveQuestions();
        store.saveExams();
        return affected;
    }

    public Question getQuestion(String questionId) throws InvalidQuestionException {
        return store.findQuestion(questionId)
                .orElseThrow(() -> new InvalidQuestionException("Question " + questionId + " was not found."));
    }

    /** Teachers see their own questions; administrators see all. */
    public List<Question> visibleQuestions(User actor) throws AccessDeniedException {
        return visibleStream(actor).sorted(DEFAULT_ORDER).collect(Collectors.toList());
    }

    private Stream<Question> visibleStream(User actor) throws AccessDeniedException {
        AccessControl.requireRole(actor, Role.TEACHER, Role.ADMIN);
        return store.getQuestions().stream()
                .filter(q -> actor.getRole() == Role.ADMIN || actor.getUserId().equals(q.getCreatedByTeacherId()));
    }

    /** Keyword search over ID, text and category. */
    public List<Question> search(User actor, String keyword) throws AccessDeniedException {
        String k = keyword == null ? "" : keyword.trim();
        return visibleStream(actor).filter(q -> k.isEmpty()
                        || TextUtil.containsIgnoreCase(q.getQuestionId() + " " + q.getText() + " " + q.getCategory(), k))
                .sorted(DEFAULT_ORDER).collect(Collectors.toList());
    }

    public List<Question> searchByCategory(User actor, String category) throws AccessDeniedException {
        String c = category == null ? "" : category.trim();
        return visibleStream(actor).filter(q -> TextUtil.containsIgnoreCase(q.getCategory(), c))
                .sorted(DEFAULT_ORDER).collect(Collectors.toList());
    }

    /** Filters by subject, type and difficulty; null means "any". */
    public List<Question> filter(User actor, String subjectId, QuestionType type, Difficulty difficulty,
                                 Comparator<Question> order) throws AccessDeniedException {
        return visibleStream(actor)
                .filter(q -> subjectId == null || q.getSubjectId().equals(subjectId))
                .filter(q -> type == null || q.getType() == type)
                .filter(q -> difficulty == null || q.getDifficulty() == difficulty)
                .sorted(order == null ? DEFAULT_ORDER : order).collect(Collectors.toList());
    }

    public List<String> categories(User actor) throws AccessDeniedException {
        return visibleStream(actor).map(Question::getCategory)
                .collect(Collectors.toCollection(() -> new TreeSet<>(String.CASE_INSENSITIVE_ORDER)))
                .stream().collect(Collectors.toList());
    }

    private Question requireOwned(User actor, String questionId) throws QuizSystemException {
        Teacher t = AccessControl.requireTeacher(actor);
        Question q = getQuestion(questionId);
        if (!t.getUserId().equals(q.getCreatedByTeacherId())) {
            throw new AccessDeniedException("You can only change questions you created.");
        }
        return q;
    }

    private void requireClassExists(String classId) throws InvalidQuestionException {
        if (classId != null && store.findClass(classId).isEmpty()) {
            throw new InvalidQuestionException("The class chosen for this question no longer exists.");
        }
    }

    /** Questions written for one class (classId null = general questions). */
    public List<Question> forClass(User actor, String classId) throws AccessDeniedException {
        return visibleStream(actor).filter(q -> java.util.Objects.equals(q.getClassId(), classId))
                .sorted(DEFAULT_ORDER).collect(Collectors.toList());
    }

    private void requireTeachesSubject(Teacher t, String subjectId) throws QuizSystemException {
        Subject s = store.findSubject(subjectId).orElseThrow(() -> new InvalidQuestionException("Subject not found."));
        if (!t.getUserId().equals(s.getTeacherId())) {
            throw new AccessDeniedException("You are not assigned to teach " + s.getCode() + ".");
        }
    }
}
