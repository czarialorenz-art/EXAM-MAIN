package data;

import exceptions.DataPersistenceException;
import exceptions.ValidationException;
import models.Exam;
import models.ExamSession;
import models.Question;
import models.Result;
import models.SchoolClass;
import models.Subject;
import models.User;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory repository of every record, backed by {@link FileManager}.
 * Services read and change records through this class and call the matching save method
 * immediately afterwards, so changes survive a restart.
 */
public class DataStore {
    public static final String USERS = "users.dat";
    public static final String SUBJECTS = "subjects.dat";
    public static final String CLASSES = "classes.dat";
    public static final String QUESTIONS = "questions.dat";
    public static final String EXAMS = "exams.dat";
    public static final String SESSIONS = "sessions.dat";
    public static final String RESULTS = "results.dat";
    public static final String IDS = "ids.dat";

    private final FileManager files;
    private LinkedHashMap<String, User> users = new LinkedHashMap<>();
    /** lower-case username -> userId, for O(1) login and duplicate checks. Rebuilt on load. */
    private final HashMap<String, String> userIdByUsername = new HashMap<>();
    private LinkedHashMap<String, Subject> subjects = new LinkedHashMap<>();
    private LinkedHashMap<String, SchoolClass> classes = new LinkedHashMap<>();
    private ArrayList<Question> questions = new ArrayList<>();
    private ArrayList<Exam> exams = new ArrayList<>();
    private LinkedHashMap<String, ExamSession> sessions = new LinkedHashMap<>();
    private ArrayList<Result> results = new ArrayList<>();
    private IdGenerator ids = new IdGenerator();

    public DataStore(FileManager files) {
        this.files = files;
    }

    @SuppressWarnings("unchecked")
    public synchronized void loadAll() {
        users = files.load(USERS, LinkedHashMap.class, LinkedHashMap::new);
        subjects = files.load(SUBJECTS, LinkedHashMap.class, LinkedHashMap::new);
        classes = files.load(CLASSES, LinkedHashMap.class, LinkedHashMap::new);
        questions = files.load(QUESTIONS, ArrayList.class, ArrayList::new);
        exams = files.load(EXAMS, ArrayList.class, ArrayList::new);
        sessions = files.load(SESSIONS, LinkedHashMap.class, LinkedHashMap::new);
        results = files.load(RESULTS, ArrayList.class, ArrayList::new);
        ids = files.load(IDS, IdGenerator.class, IdGenerator::new);
        userIdByUsername.clear();
        for (User u : users.values()) {
            userIdByUsername.put(key(u.getUsername()), u.getUserId());
        }
        // Keep the ID counters ahead of every existing record, even if ids.dat was lost.
        users.keySet().forEach(ids::ensureAbove);
        subjects.keySet().forEach(ids::ensureAbove);
        classes.keySet().forEach(ids::ensureAbove);
        questions.forEach(q -> ids.ensureAbove(q.getQuestionId()));
        exams.forEach(e -> ids.ensureAbove(e.getExamId()));
        sessions.keySet().forEach(ids::ensureAbove);
        results.forEach(r -> ids.ensureAbove(r.getResultId()));
    }

    public FileManager getFiles() {
        return files;
    }

    public List<String> getLoadWarnings() {
        return files.getWarnings();
    }

    public synchronized String nextId(String prefix) throws DataPersistenceException {
        String id = ids.next(prefix);
        files.save(IDS, ids);
        return id;
    }

    private static String key(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ users

    public Collection<User> getUsers() {
        return Collections.unmodifiableCollection(users.values());
    }

    public Optional<User> findUser(String userId) {
        return Optional.ofNullable(userId == null ? null : users.get(userId));
    }

    public Optional<User> findUserByUsername(String username) {
        String id = userIdByUsername.get(key(username));
        return id == null ? Optional.empty() : findUser(id);
    }

    public void addUser(User user) {
        users.put(user.getUserId(), user);
        userIdByUsername.put(key(user.getUsername()), user.getUserId());
    }

    public void removeUser(String userId) {
        User u = users.remove(userId);
        if (u != null) {
            userIdByUsername.remove(key(u.getUsername()));
        }
    }

    /** Changes a username and keeps the lookup index consistent. */
    public void renameUser(User user, String newUsername) throws ValidationException {
        String old = key(user.getUsername());
        user.setUsername(newUsername);
        userIdByUsername.remove(old);
        userIdByUsername.put(key(user.getUsername()), user.getUserId());
    }

    public int userCount() {
        return users.size();
    }

    // ------------------------------------------------------------------ subjects & classes

    public Collection<Subject> getSubjects() {
        return Collections.unmodifiableCollection(subjects.values());
    }

    public Optional<Subject> findSubject(String id) {
        return Optional.ofNullable(id == null ? null : subjects.get(id));
    }

    public void addSubject(Subject s) {
        subjects.put(s.getSubjectId(), s);
    }

    public void removeSubject(String id) {
        subjects.remove(id);
    }

    public Collection<SchoolClass> getClasses() {
        return Collections.unmodifiableCollection(classes.values());
    }

    public Optional<SchoolClass> findClass(String id) {
        return Optional.ofNullable(id == null ? null : classes.get(id));
    }

    public void addClass(SchoolClass c) {
        classes.put(c.getClassId(), c);
    }

    public void removeClass(String id) {
        classes.remove(id);
    }

    // ------------------------------------------------------------------ questions

    public List<Question> getQuestions() {
        return Collections.unmodifiableList(questions);
    }

    public Optional<Question> findQuestion(String id) {
        return questions.stream().filter(q -> q.getQuestionId().equals(id)).findFirst();
    }

    public void addQuestion(Question q) {
        questions.add(q);
    }

    public void replaceQuestion(Question q) {
        for (int i = 0; i < questions.size(); i++) {
            if (questions.get(i).getQuestionId().equals(q.getQuestionId())) {
                questions.set(i, q);
                return;
            }
        }
        questions.add(q);
    }

    public void removeQuestion(String id) {
        questions.removeIf(q -> q.getQuestionId().equals(id));
    }

    // ------------------------------------------------------------------ exams

    public List<Exam> getExams() {
        return Collections.unmodifiableList(exams);
    }

    public Optional<Exam> findExam(String id) {
        return exams.stream().filter(e -> e.getExamId().equals(id)).findFirst();
    }

    public void addExam(Exam e) {
        exams.add(e);
    }

    public void removeExam(String id) {
        exams.removeIf(e -> e.getExamId().equals(id));
    }

    // ------------------------------------------------------------------ sessions & results

    public Collection<ExamSession> getSessions() {
        return Collections.unmodifiableCollection(sessions.values());
    }

    public Optional<ExamSession> findSession(String id) {
        return Optional.ofNullable(id == null ? null : sessions.get(id));
    }

    public void addSession(ExamSession s) {
        sessions.put(s.getSessionId(), s);
    }

    public List<Result> getResults() {
        return Collections.unmodifiableList(results);
    }

    public Optional<Result> findResult(String id) {
        return results.stream().filter(r -> r.getResultId().equals(id)).findFirst();
    }

    public Optional<Result> findResultBySession(String sessionId) {
        return results.stream().filter(r -> r.getSessionId().equals(sessionId)).findFirst();
    }

    public void addResult(Result r) {
        results.add(r);
    }

    // ------------------------------------------------------------------ persistence

    public synchronized void saveUsers() throws DataPersistenceException {
        files.save(USERS, users);
    }

    public synchronized void saveSubjects() throws DataPersistenceException {
        files.save(SUBJECTS, subjects);
    }

    public synchronized void saveClasses() throws DataPersistenceException {
        files.save(CLASSES, classes);
    }

    public synchronized void saveQuestions() throws DataPersistenceException {
        files.save(QUESTIONS, questions);
    }

    public synchronized void saveExams() throws DataPersistenceException {
        files.save(EXAMS, exams);
    }

    public synchronized void saveSessions() throws DataPersistenceException {
        files.save(SESSIONS, sessions);
    }

    public synchronized void saveResults() throws DataPersistenceException {
        files.save(RESULTS, results);
    }

    public synchronized void saveAll() throws DataPersistenceException {
        saveUsers();
        saveSubjects();
        saveClasses();
        saveQuestions();
        saveExams();
        saveSessions();
        saveResults();
        files.save(IDS, ids);
    }

    /** Record counts for the "data management" screen. */
    public Map<String, Integer> recordCounts() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("Users", users.size());
        m.put("Subjects", subjects.size());
        m.put("Classes", classes.size());
        m.put("Questions", questions.size());
        m.put("Exams", exams.size());
        m.put("Exam sessions", sessions.size());
        m.put("Results", results.size());
        return m;
    }
}
