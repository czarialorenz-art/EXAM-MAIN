package services;

import data.DataStore;
import exceptions.AccessDeniedException;
import exceptions.QuizSystemException;
import exceptions.ValidationException;
import models.Role;
import models.SchoolClass;
import models.Student;
import models.Subject;
import models.Teacher;
import models.User;
import util.TextUtil;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/** Subjects, classes (sections) and enrolment. */
public class SubjectService {
    public static final Comparator<Subject> BY_CODE = Comparator.comparing(Subject::getCode);
    public static final Comparator<Subject> BY_NAME = Comparator.comparing(Subject::getName, String.CASE_INSENSITIVE_ORDER);

    private final DataStore store;

    public SubjectService(DataStore store) {
        this.store = store;
    }

    // ------------------------------------------------------------------ subjects

    public Subject createSubject(User actor, String code, String name, String description) throws QuizSystemException {
        AccessControl.requireRole(actor, Role.ADMIN);
        ensureCodeAvailable(Subject.normalizeCode(code), null);
        Subject s = new Subject(store.nextId("SUB"), code, name, description);
        store.addSubject(s);
        store.saveSubjects();
        return s;
    }

    public void updateSubject(User actor, String subjectId, String code, String name, String description)
            throws QuizSystemException {
        AccessControl.requireRole(actor, Role.ADMIN);
        Subject s = getSubject(subjectId);
        ensureCodeAvailable(Subject.normalizeCode(code), subjectId);
        new Subject(subjectId, code, name, description); // validates every field before changing anything
        s.setCode(code);
        s.setName(name);
        s.setDescription(description);
        store.saveSubjects();
    }

    private void ensureCodeAvailable(String code, String exceptId) throws ValidationException {
        for (Subject s : store.getSubjects()) {
            if (s.getCode().equals(code) && !s.getSubjectId().equals(exceptId)) {
                throw new ValidationException("Subject code " + code + " already exists.");
            }
        }
    }

    public void deleteSubject(User actor, String subjectId) throws QuizSystemException {
        AccessControl.requireRole(actor, Role.ADMIN);
        Subject s = getSubject(subjectId);
        long exams = store.getExams().stream().filter(e -> e.getSubjectId().equals(subjectId)).count();
        long questions = store.getQuestions().stream().filter(q -> q.getSubjectId().equals(subjectId)).count();
        if (exams > 0 || questions > 0) {
            throw new ValidationException("Cannot delete " + s.getCode() + ": it has " + exams + " exam(s) and "
                    + questions + " question(s).");
        }
        store.removeSubject(subjectId);
        store.saveSubjects();
    }

    /** Assigns a teacher (or unassigns when teacherId is null). */
    public void assignTeacher(User actor, String subjectId, String teacherId) throws QuizSystemException {
        AccessControl.requireRole(actor, Role.ADMIN);
        Subject s = getSubject(subjectId);
        if (teacherId != null) {
            User u = store.findUser(teacherId).orElseThrow(() -> new ValidationException("Teacher not found."));
            if (!(u instanceof Teacher) || !u.isActive()) {
                throw new ValidationException(u.getFullName() + " is not an active teacher.");
            }
        }
        s.setTeacherId(teacherId);
        store.saveSubjects();
    }

    public boolean enrollStudent(User actor, String subjectId, String studentId) throws QuizSystemException {
        Subject s = getSubject(subjectId);
        requireSubjectManager(actor, s);
        Student student = requireStudentRecord(studentId);
        boolean added = s.enroll(student.getUserId());
        store.saveSubjects();
        return added;
    }

    public boolean unenrollStudent(User actor, String subjectId, String studentId) throws QuizSystemException {
        Subject s = getSubject(subjectId);
        requireSubjectManager(actor, s);
        boolean removed = s.unenroll(studentId);
        store.saveSubjects();
        return removed;
    }

    // ------------------------------------------------------------------ classes

    public SchoolClass createClass(User actor, String name) throws QuizSystemException {
        AccessControl.requireRole(actor, Role.ADMIN);
        for (SchoolClass c : store.getClasses()) {
            if (c.getName().equalsIgnoreCase(name == null ? "" : name.trim())) {
                throw new ValidationException("A class named '" + c.getName() + "' already exists.");
            }
        }
        SchoolClass c = new SchoolClass(store.nextId("CLS"), name);
        store.addClass(c);
        store.saveClasses();
        return c;
    }

    public void deleteClass(User actor, String classId) throws QuizSystemException {
        AccessControl.requireRole(actor, Role.ADMIN);
        SchoolClass c = getSchoolClass(classId);
        long exams = store.getExams().stream().filter(e -> e.getAssignedClassIds().contains(classId)).count();
        if (exams > 0) {
            throw new ValidationException("Cannot delete " + c.getName() + ": it is assigned to " + exams + " exam(s).");
        }
        boolean questionsChanged = false;
        for (models.Question q : store.getQuestions()) {
            if (classId.equals(q.getClassId())) {
                q.setClassId(null); // becomes a general question instead of pointing at a missing class
                questionsChanged = true;
            }
        }
        store.removeClass(classId);
        store.saveClasses();
        if (questionsChanged) {
            store.saveQuestions();
        }
    }

    public boolean addStudentToClass(User actor, String classId, String studentId) throws QuizSystemException {
        AccessControl.requireRole(actor, Role.ADMIN);
        SchoolClass c = getSchoolClass(classId);
        requireStudentRecord(studentId);
        boolean added = c.addStudent(studentId);
        store.saveClasses();
        return added;
    }

    public boolean removeStudentFromClass(User actor, String classId, String studentId) throws QuizSystemException {
        AccessControl.requireRole(actor, Role.ADMIN);
        boolean removed = getSchoolClass(classId).removeStudent(studentId);
        store.saveClasses();
        return removed;
    }

    /** Enrols every member of a class in a subject. Returns how many were newly enrolled. */
    public int enrollClassInSubject(User actor, String classId, String subjectId) throws QuizSystemException {
        Subject s = getSubject(subjectId);
        requireSubjectManager(actor, s);
        int added = 0;
        for (String studentId : getSchoolClass(classId).getStudentIds()) {
            if (s.enroll(studentId)) {
                added++;
            }
        }
        store.saveSubjects();
        return added;
    }

    // ------------------------------------------------------------------ queries

    public Subject getSubject(String id) throws ValidationException {
        return store.findSubject(id).orElseThrow(() -> new ValidationException("Subject not found."));
    }

    public SchoolClass getSchoolClass(String id) throws ValidationException {
        return store.findClass(id).orElseThrow(() -> new ValidationException("Class not found."));
    }

    public List<Subject> listSubjects(Comparator<Subject> order) {
        return store.getSubjects().stream().sorted(order == null ? BY_CODE : order).collect(Collectors.toList());
    }

    public List<SchoolClass> listClasses() {
        return store.getClasses().stream().sorted(Comparator.comparing(SchoolClass::getName, String.CASE_INSENSITIVE_ORDER))
                .collect(Collectors.toList());
    }

    public List<Subject> subjectsForTeacher(String teacherId) {
        return store.getSubjects().stream().filter(s -> teacherId.equals(s.getTeacherId())).sorted(BY_CODE)
                .collect(Collectors.toList());
    }

    public List<Subject> subjectsForStudent(String studentId) {
        return store.getSubjects().stream().filter(s -> s.isEnrolled(studentId)).sorted(BY_CODE).collect(Collectors.toList());
    }

    public List<SchoolClass> classesForStudent(String studentId) {
        return store.getClasses().stream().filter(c -> c.hasStudent(studentId)).collect(Collectors.toList());
    }

    /** Enrolled students sorted by name. */
    public List<Student> enrolledStudents(String subjectId) throws ValidationException {
        return studentsByIds(getSubject(subjectId).getEnrolledStudentIds());
    }

    public List<Student> classMembers(String classId) throws ValidationException {
        return studentsByIds(getSchoolClass(classId).getStudentIds());
    }

    private List<Student> studentsByIds(java.util.Collection<String> ids) {
        return ids.stream().map(store::findUser).filter(java.util.Optional::isPresent).map(java.util.Optional::get)
                .filter(u -> u instanceof Student).map(u -> (Student) u).sorted(UserService.BY_NAME)
                .collect(Collectors.toList());
    }

    /** Searches subject code, name and assigned teacher name. */
    public List<Subject> searchSubjects(String query) {
        String q = query == null ? "" : query.trim();
        return store.getSubjects().stream().filter(s -> q.isEmpty()
                        || TextUtil.containsIgnoreCase(s.getCode() + " " + s.getName() + " " + teacherName(s), q))
                .sorted(BY_CODE).collect(Collectors.toList());
    }

    public String teacherName(Subject s) {
        return s.getTeacherId() == null ? "(unassigned)"
                : store.findUser(s.getTeacherId()).map(User::getFullName).orElse("(unknown)");
    }

    public String subjectLabel(String subjectId) {
        return store.findSubject(subjectId).map(Subject::getCode).orElse("?");
    }

    public boolean teaches(String teacherId, String subjectId) {
        return store.findSubject(subjectId).map(s -> teacherId.equals(s.getTeacherId())).orElse(false);
    }

    private void requireSubjectManager(User actor, Subject s) throws AccessDeniedException {
        AccessControl.requireRole(actor, Role.ADMIN, Role.TEACHER);
        if (actor.getRole() == Role.TEACHER && !actor.getUserId().equals(s.getTeacherId())) {
            throw new AccessDeniedException("You can only manage enrolment for subjects assigned to you.");
        }
    }

    private Student requireStudentRecord(String studentId) throws ValidationException {
        User u = store.findUser(studentId).orElseThrow(() -> new ValidationException("Student not found."));
        if (!(u instanceof Student student)) {
            throw new ValidationException(u.getFullName() + " is not a student.");
        }
        return student;
    }
}
