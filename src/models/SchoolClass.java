package models;

import exceptions.ValidationException;
import util.InputValidator;

import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** A class/section of students (e.g. "BSIT 2-A"). Exams can be assigned to a whole class. */
public class SchoolClass implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String classId;
    private String name;
    private final Set<String> studentIds = new LinkedHashSet<>();

    public SchoolClass(String classId, String name) throws ValidationException {
        this.classId = InputValidator.requireText(classId, "Class ID", 20);
        setName(name);
    }

    public String getClassId() {
        return classId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) throws ValidationException {
        this.name = InputValidator.requireText(name, "Class name", 60);
    }

    public boolean addStudent(String studentId) {
        return studentIds.add(studentId);
    }

    public boolean removeStudent(String studentId) {
        return studentIds.remove(studentId);
    }

    public boolean hasStudent(String studentId) {
        return studentIds.contains(studentId);
    }

    public Set<String> getStudentIds() {
        return Collections.unmodifiableSet(studentIds);
    }

    @Override
    public String toString() {
        return name;
    }
}
