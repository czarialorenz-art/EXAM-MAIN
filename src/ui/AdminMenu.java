package ui;

import exceptions.QuizSystemException;
import models.Admin;
import models.Exam;
import models.Role;
import models.SchoolClass;
import models.Student;
import models.Subject;
import models.Teacher;
import models.User;
import services.ExamService;
import services.ServiceRegistry;
import services.SubjectService;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Administrator dashboard: users, subjects/classes, exams overview, reports and data management. */
public class AdminMenu extends BaseMenu {
    private final ReportScreens reports;
    private Admin admin;

    public AdminMenu(ServiceRegistry services, InputReader in) {
        super(services, in);
        this.reports = new ReportScreens(services, in);
    }

    @Override
    public void show(User user) {
        admin = (Admin) user;
        runMenu("ADMINISTRATOR MENU - " + admin.getFullName(), "Logout",
                item("User management", this::userMenu),
                item("Subjects and classes", this::subjectMenu),
                item("View examinations", this::examMenu),
                item("Results and reports", () -> reports.menu(admin)),
                item("Data management", this::dataMenu),
                item("Change my password", () -> changePassword(admin)));
    }

    // ================================================================== users

    private void userMenu() {
        runMenu("USER MANAGEMENT", "Back",
                item("List all users", () -> printUsers(services.users().listUsers(null))),
                item("List users by role", () -> {
                    Role r = choose("Role", List.of(Role.values()), Role::getLabel);
                    if (r != null) {
                        printUsers(services.users().listUsers(r));
                    }
                }),
                item("Search users (name, username, ID, student number)", () ->
                        printUsers(services.users().search(in.line("Search for: "), null))),
                item("Add a student", this::addStudent),
                item("Add a teacher", this::addTeacher),
                item("Add an administrator", this::addAdmin),
                item("View user details", () -> {
                    User u = pickUser();
                    if (u != null) {
                        printUserDetails(u);
                    }
                }),
                item("Edit a user", this::editUser),
                item("Activate / deactivate a user", this::toggleActive),
                item("Reset a user's password", () -> {
                    User u = pickUser();
                    if (u != null) {
                        services.users().resetPassword(admin, u.getUserId(), in.password("New password for " + u.getUsername() + ": "));
                        success("Password reset. Tell the user their new password.");
                    }
                }),
                item("Delete a user", () -> {
                    User u = pickUser();
                    if (u != null && confirm("Permanently delete " + u.getFullName() + "?")) {
                        services.users().deleteUser(admin, u.getUserId());
                        success("User deleted.");
                    }
                }));
    }

    private User pickUser() {
        return choose("Choose a user", services.users().listUsers(null), this::userLabel);
    }

    private void printUserDetails(User u) {
        sub("User " + u.getUserId());
        out.println("  Username : " + u.getUsername());
        out.println("  Name     : " + u.getFullName());
        out.println("  Email    : " + (u.getEmail().isEmpty() ? "-" : u.getEmail()));
        out.println("  Role     : " + u.getRole().getLabel() + "  (" + u.getDisplaySummary() + ")");
        out.println("  Status   : " + (u.isActive() ? "Active" : "Inactive"));
        if (u instanceof Teacher) {
            out.println("  Subjects : " + services.subjects().subjectsForTeacher(u.getUserId()));
        } else if (u instanceof Student) {
            out.println("  Subjects : " + services.subjects().subjectsForStudent(u.getUserId()));
            out.println("  Classes  : " + services.subjects().classesForStudent(u.getUserId()));
        }
    }

    private String[] askCommon() {
        String username = in.required("Username (4-20 letters/digits/_/.): ");
        String fullName = in.required("Full name: ");
        String email = in.line("Email (optional): ");
        String password = in.password("Initial password (min 8 chars, letters and digits): ");
        return new String[]{username, fullName, email, password};
    }

    private void addStudent() throws QuizSystemException {
        sub("New student");
        String[] c = askCommon();
        String number = in.required("Student number: ");
        String year = in.line("Year level (optional): ");
        Student s = services.users().createStudent(admin, c[0], c[3], c[1], c[2], number, year);
        success("Student " + s.getFullName() + " created with ID " + s.getUserId() + ".");
    }

    private void addTeacher() throws QuizSystemException {
        sub("New teacher");
        String[] c = askCommon();
        String dept = in.line("Department (optional): ");
        Teacher t = services.users().createTeacher(admin, c[0], c[3], c[1], c[2], dept);
        success("Teacher " + t.getFullName() + " created with ID " + t.getUserId() + ".");
    }

    private void addAdmin() throws QuizSystemException {
        sub("New administrator");
        String[] c = askCommon();
        Admin a = services.users().createAdmin(admin, c[0], c[3], c[1], c[2]);
        success("Administrator " + a.getFullName() + " created with ID " + a.getUserId() + ".");
    }

    private void editUser() throws QuizSystemException {
        User u = pickUser();
        if (u == null) {
            return;
        }
        sub("Edit " + u.getUserId() + " (Enter keeps the current value)");
        String username = in.optional("Username", u.getUsername());
        String name = in.optional("Full name", u.getFullName());
        String email = in.optional("Email ('-' to clear)", u.getEmail());
        if ("-".equals(email)) {
            email = "";
        }
        String detail = u.getRoleDetailLabel() == null ? "" : in.optional(u.getRoleDetailLabel(), u.getRoleDetail());
        services.users().updateUser(admin, u.getUserId(), username, name, email, detail);
        success("User updated.");
    }

    private void toggleActive() throws QuizSystemException {
        User u = pickUser();
        if (u == null) {
            return;
        }
        boolean activate = !u.isActive();
        if (confirm((activate ? "Activate " : "Deactivate ") + u.getFullName() + "?")) {
            services.users().setActive(admin, u.getUserId(), activate);
            success(u.getFullName() + " is now " + (activate ? "active." : "inactive and cannot log in."));
        }
    }

    // ================================================================== subjects & classes

    private void subjectMenu() {
        runMenu("SUBJECTS AND CLASSES", "Back",
                item("List subjects (sorted by code)", () -> printSubjects(services.subjects().listSubjects(SubjectService.BY_CODE))),
                item("List subjects (sorted by name)", () -> printSubjects(services.subjects().listSubjects(SubjectService.BY_NAME))),
                item("Search subjects (code, name, teacher)", () -> printSubjects(services.subjects().searchSubjects(in.line("Search for: ")))),
                item("Add a subject", () -> {
                    Subject s = services.subjects().createSubject(admin, in.required("Subject code (e.g. CS201): "),
                            in.required("Subject name: "), in.line("Description (optional): "));
                    success("Subject " + s + " created.");
                }),
                item("Edit a subject", () -> {
                    Subject s = pickSubject();
                    if (s != null) {
                        services.subjects().updateSubject(admin, s.getSubjectId(), in.optional("Code", s.getCode()),
                                in.optional("Name", s.getName()), in.optional("Description", s.getDescription()));
                        success("Subject updated.");
                    }
                }),
                item("Delete a subject", () -> {
                    Subject s = pickSubject();
                    if (s != null && confirm("Delete " + s + "? Its questions are deleted too. This cannot be undone.")) {
                        services.subjects().deleteSubject(admin, s.getSubjectId());
                        success("Subject deleted.");
                    }
                }),
                item("Assign a teacher to a subject", () -> {
                    Subject s = pickSubject();
                    if (s == null) {
                        return;
                    }
                    Teacher t = choose("Choose a teacher", services.users().listTeachers(), this::userLabel);
                    if (t != null) {
                        services.subjects().assignTeacher(admin, s.getSubjectId(), t.getUserId());
                        success(t.getFullName() + " now teaches " + s.getCode() + ".");
                    }
                }),
                item("Enrol a student in a subject", () -> {
                    Subject s = pickSubject();
                    Student st = s == null ? null : choose("Choose a student", services.users().listStudents(), this::userLabel);
                    if (st != null) {
                        boolean added = services.subjects().enrollStudent(admin, s.getSubjectId(), st.getUserId());
                        success(added ? st.getFullName() + " enrolled in " + s.getCode() + "." : "Already enrolled.");
                    }
                }),
                item("Remove a student from a subject", () -> {
                    Subject s = pickSubject();
                    Student st = s == null ? null
                            : choose("Choose a student", services.subjects().enrolledStudents(s.getSubjectId()), this::userLabel);
                    if (st != null) {
                        services.subjects().unenrollStudent(admin, s.getSubjectId(), st.getUserId());
                        success("Student removed from " + s.getCode() + ".");
                    }
                }),
                item("View students enrolled in a subject", () -> {
                    Subject s = pickSubject();
                    if (s != null) {
                        printUsers(services.subjects().enrolledStudents(s.getSubjectId()));
                    }
                }),
                item("List classes", this::printClasses),
                item("Create a class", () -> {
                    SchoolClass c = services.subjects().createClass(admin, in.required("Class name (e.g. BSIT 2-A): "));
                    success("Class " + c.getName() + " created.");
                }),
                item("Add students to a class", () -> {
                    SchoolClass c = pickClass();
                    if (c == null) {
                        return;
                    }
                    List<Student> picked = chooseMany("Students", services.users().listStudents(), this::userLabel);
                    int added = 0;
                    for (Student s : picked) {
                        if (services.subjects().addStudentToClass(admin, c.getClassId(), s.getUserId())) {
                            added++;
                        }
                    }
                    success(added + " student(s) added to " + c.getName() + ".");
                }),
                item("Remove a student from a class", () -> {
                    SchoolClass c = pickClass();
                    Student s = c == null ? null : choose("Choose a student", services.subjects().classMembers(c.getClassId()), this::userLabel);
                    if (s != null) {
                        services.subjects().removeStudentFromClass(admin, c.getClassId(), s.getUserId());
                        success("Student removed from " + c.getName() + ".");
                    }
                }),
                item("View class members", () -> {
                    SchoolClass c = pickClass();
                    if (c != null) {
                        printUsers(services.subjects().classMembers(c.getClassId()));
                    }
                }),
                item("Enrol a whole class in a subject", () -> {
                    SchoolClass c = pickClass();
                    Subject s = c == null ? null : pickSubject();
                    if (s != null) {
                        int n = services.subjects().enrollClassInSubject(admin, c.getClassId(), s.getSubjectId());
                        success(n + " student(s) newly enrolled in " + s.getCode() + ".");
                    }
                }),
                item("Delete a class", () -> {
                    SchoolClass c = pickClass();
                    if (c != null && confirm("Delete class " + c.getName() + "?")) {
                        services.subjects().deleteClass(admin, c.getClassId());
                        success("Class deleted.");
                    }
                }));
    }

    private Subject pickSubject() {
        return choose("Choose a subject", services.subjects().listSubjects(null), this::subjectLabel);
    }

    private SchoolClass pickClass() {
        return choose("Choose a class", services.subjects().listClasses(), this::classLabel);
    }

    private void printSubjects(List<Subject> subjects) {
        List<String[]> rows = new ArrayList<>();
        for (Subject s : subjects) {
            rows.add(new String[]{s.getSubjectId(), s.getCode(), s.getName(), services.subjects().teacherName(s),
                    String.valueOf(s.getEnrolledStudentIds().size())});
        }
        TablePrinter.print(out, new String[]{"ID", "Code", "Name", "Teacher", "Enrolled"}, rows);
    }

    private void printClasses() {
        List<String[]> rows = new ArrayList<>();
        for (SchoolClass c : services.subjects().listClasses()) {
            rows.add(new String[]{c.getClassId(), c.getName(), String.valueOf(c.getStudentIds().size())});
        }
        TablePrinter.print(out, new String[]{"ID", "Name", "Students"}, rows);
    }

    // ================================================================== exams

    private void examMenu() {
        runMenu("ALL EXAMINATIONS", "Back",
                item("List all exams (sorted by date)", () -> printExams(services.exams().examsFor(admin, ExamService.BY_DATE))),
                item("List all exams (sorted by title)", () -> printExams(services.exams().examsFor(admin, ExamService.BY_TITLE))),
                item("Search exams by title", () -> printExams(services.exams().searchByTitle(admin, in.line("Title contains: ")))),
                item("View exam details", () -> {
                    Exam e = choose("Choose an exam", services.exams().examsFor(admin, null), this::examLabel);
                    if (e != null) {
                        sub("Exam details");
                        printExamDetails(e);
                        out.println("  Teacher        : " + services.users().nameOf(e.getTeacherId()));
                        out.println("  Assigned to    : " + services.exams().assignmentSummary(e));
                    }
                }));
    }

    // ================================================================== data

    private void dataMenu() {
        runMenu("DATA MANAGEMENT", "Back",
                item("Show data status", () -> {
                    sub("Data folder: " + services.store().getFiles().getDataDir());
                    for (Map.Entry<String, Integer> e : services.store().recordCounts().entrySet()) {
                        out.printf("  %-15s %d%n", e.getKey(), e.getValue());
                    }
                    List<String> warnings = services.store().getLoadWarnings();
                    out.println(warnings.isEmpty() ? "  No problems were found when the data was loaded."
                            : "  Load warnings:");
                    warnings.forEach(w -> out.println("   - " + w));
                }),
                item("Save all data now", () -> {
                    services.store().saveAll();
                    success("All records saved.");
                }),
                item("Create a backup copy of all data files", () -> {
                    Path p = services.store().getFiles().backupAll();
                    success("Backup created in " + p);
                }));
    }
}
