package models;

import exceptions.ValidationException;
import util.InputValidator;

/** Teacher account: owns questions and examinations for the subjects assigned to them. */
public class Teacher extends User {
    private static final long serialVersionUID = 1L;

    private String department;

    public Teacher(String userId, String username, String plainPassword, String fullName, String email,
                   String department) throws ValidationException {
        super(userId, username, plainPassword, fullName, email);
        setDepartment(department);
    }

    public String getDepartment() {
        return department;
    }

    public void setDepartment(String department) throws ValidationException {
        this.department = InputValidator.optionalText(department, "Department", 60);
    }

    @Override
    public Role getRole() {
        return Role.TEACHER;
    }

    @Override
    public String getDisplaySummary() {
        return department.isEmpty() ? "Teacher" : "Teacher, " + department;
    }

    @Override
    public String getRoleDetailLabel() {
        return "Department";
    }

    @Override
    public String getRoleDetail() {
        return department;
    }

    @Override
    public void setRoleDetail(String value) throws ValidationException {
        setDepartment(value);
    }

    @Override
    public String getSearchText() {
        return super.getSearchText() + " " + department;
    }
}
