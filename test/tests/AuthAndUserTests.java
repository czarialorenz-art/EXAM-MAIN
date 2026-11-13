package tests;

import exceptions.AccessDeniedException;
import exceptions.AuthenticationException;
import exceptions.DuplicateUserException;
import exceptions.ValidationException;
import models.Role;
import models.Student;
import models.User;

import static tests.TestRunner.check;
import static tests.TestRunner.eq;
import static tests.TestRunner.expectThrows;
import static tests.TestRunner.suite;
import static tests.TestRunner.test;

final class AuthAndUserTests {
    private AuthAndUserTests() {
    }

    static void run() {
        suite("Authentication");
        test("valid login returns the user with the right role", () -> {
            Fixture f = new Fixture();
            User u = f.s.auth().login("teacher1", "Teacher123");
            eq(Role.TEACHER, u.getRole(), "role");
        });
        test("login is case-insensitive for the username only", () -> {
            Fixture f = new Fixture();
            eq(f.alice, f.s.auth().login("ALICE", "Student123"), "user");
            expectThrows(AuthenticationException.class, () -> f.s.auth().login("alice", "student123"));
        });
        test("wrong password and unknown username give the same error", () -> {
            Fixture f = new Fixture();
            String a = expectThrows(AuthenticationException.class, () -> f.s.auth().login("alice", "Wrong999")).getMessage();
            String b = expectThrows(AuthenticationException.class, () -> f.s.auth().login("nobody", "Wrong999")).getMessage();
            check(a.startsWith("Invalid username or password") && b.startsWith("Invalid username or password"), a + " / " + b);
        });
        test("account is locked after 5 failed attempts, admin reset unlocks it", () -> {
            Fixture f = new Fixture();
            for (int i = 0; i < 5; i++) {
                expectThrows(AuthenticationException.class, () -> f.s.auth().login("bobby", "Wrong999"));
            }
            expectThrows(AuthenticationException.class, () -> f.s.auth().login("bobby", "Student123"));
            f.s.users().resetPassword(f.admin, f.bob.getUserId(), "NewPass123");
            eq(f.bob, f.s.auth().login("bobby", "NewPass123"), "unlocked after reset");
        });
        test("deactivated accounts cannot log in", () -> {
            Fixture f = new Fixture();
            f.s.users().setActive(f.admin, f.carol.getUserId(), false);
            expectThrows(AuthenticationException.class, () -> f.s.auth().login("carol", "Student123"));
        });
        test("passwords are hashed with a salt, never stored in plain text", () -> {
            Fixture f = new Fixture();
            f.s.store().saveUsers();
            String raw = new String(java.nio.file.Files.readAllBytes(f.dir.resolve("users.dat")), java.nio.charset.StandardCharsets.ISO_8859_1);
            check(!raw.contains("Student123") && !raw.contains("Teacher123"), "plain password found in users.dat");
            check(f.alice.verifyPassword("Student123"), "hash verifies");
        });
        test("weak passwords are rejected", () -> {
            Fixture f = new Fixture();
            expectThrows(ValidationException.class, () -> f.s.users().createStudent(f.admin, "dave", "short1", "Dave", "", "S-9", ""));
            expectThrows(ValidationException.class, () -> f.s.users().createStudent(f.admin, "dave", "lettersonly", "Dave", "", "S-9", ""));
            expectThrows(ValidationException.class, () -> f.s.users().createStudent(f.admin, "dave", "12345678", "Dave", "", "S-9", ""));
        });
        test("password change requires the current password and matching confirmation", () -> {
            Fixture f = new Fixture();
            expectThrows(ValidationException.class, () -> f.s.auth().changePassword(f.alice, "Wrong123", "Brand123", "Brand123"));
            expectThrows(ValidationException.class, () -> f.s.auth().changePassword(f.alice, "Student123", "Brand123", "Other123"));
            f.s.auth().changePassword(f.alice, "Student123", "Brand123", "Brand123");
            eq(f.alice, f.s.auth().login("alice", "Brand123"), "new password works");
        });
        test("first-run admin setup is only possible once", () -> {
            Fixture f = new Fixture();
            expectThrows(AccessDeniedException.class, () -> f.s.auth().createInitialAdmin("admin2", "Admin123", "X", ""));
        });

        suite("Role-based access");
        test("students and teachers cannot use admin functions", () -> {
            Fixture f = new Fixture();
            expectThrows(AccessDeniedException.class, () -> f.s.users().createTeacher(f.alice, "hack", "Hack1234", "H", "", ""));
            expectThrows(AccessDeniedException.class, () -> f.s.users().deleteUser(f.teacher, f.bob.getUserId()));
            expectThrows(AccessDeniedException.class, () -> f.s.subjects().createSubject(f.teacher, "X1", "X", ""));
        });
        test("students cannot create questions or exams", () -> {
            Fixture f = new Fixture();
            expectThrows(AccessDeniedException.class, () -> f.s.exams().createExam(f.alice, f.subject.getSubjectId(), "Mine", ""));
            expectThrows(AccessDeniedException.class, () -> f.s.questions().visibleQuestions(f.alice));
        });
        test("a teacher cannot manage another teacher's subject", () -> {
            Fixture f = new Fixture();
            var other = f.s.users().createTeacher(f.admin, "teacher2", "Teacher123", "Other T", "", "");
            expectThrows(AccessDeniedException.class, () -> f.s.subjects().enrollStudent(other, f.subject.getSubjectId(), f.alice.getUserId()));
            expectThrows(AccessDeniedException.class, () -> f.s.exams().createExam(other, f.subject.getSubjectId(), "T", ""));
        });

        suite("User management");
        test("duplicate usernames are rejected (case-insensitive)", () -> {
            Fixture f = new Fixture();
            expectThrows(DuplicateUserException.class, () -> f.s.users().createStudent(f.admin, "Alice", "Student123", "A2", "", "S-100", ""));
        });
        test("duplicate student numbers are rejected", () -> {
            Fixture f = new Fixture();
            expectThrows(DuplicateUserException.class, () -> f.s.users().createStudent(f.admin, "newbie", "Student123", "N", "", "s-001", ""));
        });
        test("invalid account data is rejected (username, email, blank name)", () -> {
            Fixture f = new Fixture();
            expectThrows(ValidationException.class, () -> f.s.users().createTeacher(f.admin, "a b", "Teacher123", "X", "", ""));
            expectThrows(ValidationException.class, () -> f.s.users().createTeacher(f.admin, "valid1", "Teacher123", "X", "not-an-email", ""));
            expectThrows(ValidationException.class, () -> f.s.users().createTeacher(f.admin, "valid1", "Teacher123", "  ", "", ""));
        });
        test("edit user updates fields and keeps the username index consistent", () -> {
            Fixture f = new Fixture();
            f.s.users().updateUser(f.admin, f.bob.getUserId(), "robert", "Robert Cruz", "rob@test.edu", "2nd Year");
            eq("Robert Cruz", f.bob.getFullName(), "name");
            eq("2nd Year", ((Student) f.bob).getYearLevel(), "year level (polymorphic detail)");
            eq(f.bob, f.s.auth().login("robert", "Student123"), "login with new username");
            expectThrows(AuthenticationException.class, () -> f.s.auth().login("bobby", "Student123"));
        });
        test("failed edit leaves the record unchanged", () -> {
            Fixture f = new Fixture();
            expectThrows(DuplicateUserException.class, () -> f.s.users().updateUser(f.admin, f.bob.getUserId(), "alice", "Changed", "", ""));
            eq("Bob Cruz", f.bob.getFullName(), "name unchanged");
        });
        test("delete user without history; users with history must be deactivated", () -> {
            Fixture f = new Fixture();
            f.s.users().deleteUser(f.admin, f.carol.getUserId());
            check(f.s.store().findUser(f.carol.getUserId()).isEmpty(), "carol deleted");
            check(!f.subject.isEnrolled(f.carol.getUserId()), "enrolment removed");
            f.mcq("Q?", 0, 1);
            expectThrows(ValidationException.class, () -> f.s.users().deleteUser(f.admin, f.teacher.getUserId()));
            expectThrows(ValidationException.class, () -> f.s.users().deleteUser(f.admin, f.admin.getUserId()));
        });
    }
}
