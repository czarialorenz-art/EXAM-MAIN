package models;

import exceptions.ValidationException;
import util.InputValidator;

import java.util.Locale;

/** Student account: takes assigned examinations and views their own released results. */
public class Student extends User {
    private static final long serialVersionUID = 1L;

    private String studentNumber;
    private String yearLevel;

    public Student(String userId, String username, String plainPassword, String fullName, String email,
                   String studentNumber, String yearLevel) throws ValidationException {
        super(userId, username, plainPassword, fullName, email);
        setStudentNumber(studentNumber);
        setYearLevel(yearLevel);
    }

    public String getStudentNumber() {
        return studentNumber;
    }

    public final void setStudentNumber(String studentNumber) throws ValidationException {
        String n = InputValidator.requireText(studentNumber, "Student number", 20).toUpperCase(Locale.ROOT);
        if (!n.matches("[A-Z0-9-]{3,20}")) {
            throw new ValidationException("Student number must be 3-20 letters, digits or dashes.");
        }
        this.studentNumber = n;
    }

    public String getYearLevel() {
        return yearLevel;
    }

    public final void setYearLevel(String yearLevel) throws ValidationException {
        this.yearLevel = InputValidator.optionalText(yearLevel, "Year level", 30);
    }

    @Override
    public Role getRole() {
        return Role.STUDENT;
    }

    @Override
    public String getDisplaySummary() {
        return "Student No. " + studentNumber + (yearLevel.isEmpty() ? "" : ", " + yearLevel);
    }

    @Override
    public String getRoleDetailLabel() {
        return "Year level";
    }

    @Override
    public String getRoleDetail() {
        return yearLevel;
    }

    @Override
    public void setRoleDetail(String value) throws ValidationException {
        setYearLevel(value);
    }

    @Override
    public String getSearchText() {
        return super.getSearchText() + " " + studentNumber;
    }
}
