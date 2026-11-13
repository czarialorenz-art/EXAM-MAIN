package models;

import exceptions.ValidationException;
import util.InputValidator;

import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** A subject/course. One teacher is assigned; students are enrolled. */
public class Subject implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String subjectId;
    private String code;
    private String name;
    private String description;
    private String teacherId;
    private final Set<String> enrolledStudentIds = new LinkedHashSet<>();

    public Subject(String subjectId, String code, String name, String description) throws ValidationException {
        this.subjectId = InputValidator.requireText(subjectId, "Subject ID", 20);
        setCode(code);
        setName(name);
        setDescription(description);
    }

    public static String normalizeCode(String code) throws ValidationException {
        String c = InputValidator.requireText(code, "Subject code", 15).toUpperCase(Locale.ROOT);
        if (!c.matches("[A-Z0-9-]{2,15}")) {
            throw new ValidationException("Subject code must be 2-15 letters, digits or dashes.");
        }
        return c;
    }

    public String getSubjectId() {
        return subjectId;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) throws ValidationException {
        this.code = normalizeCode(code);
    }

    public String getName() {
        return name;
    }

    public void setName(String name) throws ValidationException {
        this.name = InputValidator.requireText(name, "Subject name", 100);
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) throws ValidationException {
        this.description = InputValidator.optionalText(description, "Description", 300);
    }

    /** May be null when no teacher is assigned. */
    public String getTeacherId() {
        return teacherId;
    }

    public void setTeacherId(String teacherId) {
        this.teacherId = teacherId;
    }

    public boolean enroll(String studentId) {
        return enrolledStudentIds.add(studentId);
    }

    public boolean unenroll(String studentId) {
        return enrolledStudentIds.remove(studentId);
    }

    public boolean isEnrolled(String studentId) {
        return enrolledStudentIds.contains(studentId);
    }

    public Set<String> getEnrolledStudentIds() {
        return Collections.unmodifiableSet(enrolledStudentIds);
    }

    @Override
    public String toString() {
        return code + " - " + name;
    }
}
