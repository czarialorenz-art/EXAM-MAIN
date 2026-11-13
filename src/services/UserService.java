package services;

import data.DataStore;
import exceptions.DuplicateUserException;
import exceptions.QuizSystemException;
import exceptions.ValidationException;
import models.Admin;
import models.Exam;
import models.Role;
import models.SchoolClass;
import models.Student;
import models.Subject;
import models.Teacher;
import models.User;
import util.InputValidator;
import util.TextUtil;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/** Administrator user management: add, edit, deactivate, delete, search. */
public class UserService {
    public static final Comparator<User> BY_NAME = Comparator.comparing(User::getFullName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(User::getUsername, String.CASE_INSENSITIVE_ORDER);
    public static final Comparator<User> BY_ROLE_THEN_NAME = Comparator.comparing(User::getRole).thenComparing(BY_NAME);

    private final DataStore store;
    private final AuthenticationService auth;

    public UserService(DataStore store, AuthenticationService auth) {
        this.store = store;
        this.auth = auth;
    }

    public Admin createAdmin(User actor, String username, String password, String fullName, String email)
            throws QuizSystemException {
        AccessControl.requireRole(actor, Role.ADMIN);
        ensureUsernameAvailable(username, null);
        Admin admin = new Admin(store.nextId("ADM"), username, password, fullName, email);
        register(admin);
        return admin;
    }

    public Teacher createTeacher(User actor, String username, String password, String fullName, String email,
                                 String department) throws QuizSystemException {
        AccessControl.requireRole(actor, Role.ADMIN);
        ensureUsernameAvailable(username, null);
        Teacher teacher = new Teacher(store.nextId("TCH"), username, password, fullName, email, department);
        register(teacher);
        return teacher;
    }

    public Student createStudent(User actor, String username, String password, String fullName, String email,
                                 String studentNumber, String yearLevel) throws QuizSystemException {
        AccessControl.requireRole(actor, Role.ADMIN);
        ensureUsernameAvailable(username, null);
        ensureStudentNumberAvailable(studentNumber, null);
        Student student = new Student(store.nextId("STU"), username, password, fullName, email, studentNumber, yearLevel);
        register(student);
        return student;
    }

    private void register(User user) throws QuizSystemException {
        store.addUser(user);
        store.saveUsers();
    }

    void ensureUsernameAvailable(String username, String exceptUserId) throws QuizSystemException {
        String clean = InputValidator.validateUsername(username);
        Optional<User> existing = store.findUserByUsername(clean);
        if (existing.isPresent() && !existing.get().getUserId().equals(exceptUserId)) {
            throw new DuplicateUserException("The username '" + clean + "' is already taken.");
        }
    }

    void ensureStudentNumberAvailable(String studentNumber, String exceptUserId) throws QuizSystemException {
        String n = InputValidator.requireText(studentNumber, "Student number", 20);
        for (User u : store.getUsers()) {
            if (u instanceof Student s && s.getStudentNumber().equalsIgnoreCase(n) && !s.getUserId().equals(exceptUserId)) {
                throw new DuplicateUserException("Student number " + s.getStudentNumber() + " is already registered to "
                        + s.getFullName() + ".");
            }
        }
    }

    /**
     * Updates the editable profile fields. All values are validated before anything changes,
     * so a failed update leaves the record untouched.
     */
    public void updateUser(User actor, String userId, String newUsername, String fullName, String email,
                           String roleDetail) throws QuizSystemException {
        AccessControl.requireRole(actor, Role.ADMIN);
        User user = getUser(userId);
        String username = InputValidator.validateUsername(newUsername);
        ensureUsernameAvailable(username, userId);
        String name = InputValidator.requireText(fullName, "Full name", 80);
        String mail = InputValidator.validateEmail(email);
        String detail = InputValidator.optionalText(roleDetail, user.getRoleDetailLabel() == null ? "Detail"
                : user.getRoleDetailLabel(), 60);

        if (!username.equals(user.getUsername())) {
            store.renameUser(user, username);
        }
        user.setFullName(name);
        user.setEmail(mail);
        user.setRoleDetail(detail);
        store.saveUsers();
    }

    public void setActive(User actor, String userId, boolean active) throws QuizSystemException {
        AccessControl.requireRole(actor, Role.ADMIN);
        User user = getUser(userId);
        if (!active && user.getUserId().equals(actor.getUserId())) {
            throw new ValidationException("You cannot deactivate your own account.");
        }
        if (!active && user.getRole() == Role.ADMIN && countActiveAdmins() <= 1) {
            throw new ValidationException("You cannot deactivate the last active administrator.");
        }
        user.setActive(active);
        store.saveUsers();
    }

    public void resetPassword(User actor, String userId, String newPassword) throws QuizSystemException {
        AccessControl.requireRole(actor, Role.ADMIN);
        User user = getUser(userId);
        user.resetPassword(newPassword);
        auth.clearFailedAttempts(user.getUsername());
        store.saveUsers();
    }

    /**
     * Deletes a user who has no history. Users who own exams/questions or have exam attempts
     * cannot be deleted (that would orphan results) - they should be deactivated instead.
     */
    public void deleteUser(User actor, String userId) throws QuizSystemException {
        AccessControl.requireRole(actor, Role.ADMIN);
        User user = getUser(userId);
        if (user.getUserId().equals(actor.getUserId())) {
            throw new ValidationException("You cannot delete your own account.");
        }
        if (user.getRole() == Role.ADMIN && countActiveAdmins() <= 1 && user.isActive()) {
            throw new ValidationException("You cannot delete the last active administrator.");
        }
        String reason = historyReason(user);
        if (reason != null) {
            throw new ValidationException("Cannot delete " + user.getUsername() + " because " + reason
                    + ". Deactivate the account instead to keep records consistent.");
        }
        for (Subject s : store.getSubjects()) {
            if (userId.equals(s.getTeacherId())) {
                s.setTeacherId(null);
            }
            s.unenroll(userId);
        }
        for (SchoolClass c : store.getClasses()) {
            c.removeStudent(userId);
        }
        for (Exam e : store.getExams()) {
            e.unassignStudent(userId);
        }
        store.removeUser(userId);
        store.saveUsers();
        store.saveSubjects();
        store.saveClasses();
        store.saveExams();
    }

    private String historyReason(User user) {
        String id = user.getUserId();
        long exams = store.getExams().stream().filter(e -> e.getTeacherId().equals(id)).count();
        long questions = store.getQuestions().stream().filter(q -> id.equals(q.getCreatedByTeacherId())).count();
        long attempts = store.getSessions().stream().filter(s -> s.getStudentId().equals(id)).count();
        if (exams > 0 || questions > 0) {
            return "they own " + exams + " exam(s) and " + questions + " question(s)";
        }
        if (attempts > 0) {
            return "they have " + attempts + " exam attempt(s)";
        }
        return null;
    }

    private long countActiveAdmins() {
        return store.getUsers().stream().filter(u -> u.getRole() == Role.ADMIN && u.isActive()).count();
    }

    public User getUser(String userId) throws ValidationException {
        return store.findUser(userId).orElseThrow(() -> new ValidationException("No user found with ID " + userId + "."));
    }

    /** All users (optionally of one role), sorted by role then name. */
    public List<User> listUsers(Role role) {
        return store.getUsers().stream().filter(u -> role == null || u.getRole() == role)
                .sorted(BY_ROLE_THEN_NAME).collect(Collectors.toList());
    }

    /** Searches name, username, user ID, email and (for students) student number. */
    public List<User> search(String query, Role role) {
        String q = query == null ? "" : query.trim();
        return store.getUsers().stream()
                .filter(u -> role == null || u.getRole() == role)
                .filter(u -> q.isEmpty() || TextUtil.containsIgnoreCase(u.getSearchText(), q))
                .sorted(BY_ROLE_THEN_NAME).collect(Collectors.toList());
    }

    public List<Student> listStudents() {
        return store.getUsers().stream().filter(u -> u instanceof Student).map(u -> (Student) u)
                .sorted(BY_NAME).collect(Collectors.toList());
    }

    public List<Teacher> listTeachers() {
        return store.getUsers().stream().filter(u -> u instanceof Teacher).map(u -> (Teacher) u)
                .sorted(BY_NAME).collect(Collectors.toList());
    }

    public String nameOf(String userId) {
        return store.findUser(userId).map(User::getFullName).orElse("(unknown user " + userId + ")");
    }
}
