package services;

import exceptions.AccessDeniedException;
import models.Role;
import models.Student;
import models.Teacher;
import models.User;

/**
 * Role checks used by every service method. Permissions are enforced here, in the service
 * layer, so they hold even if a menu shows the wrong option.
 */
public final class AccessControl {
    private AccessControl() {
    }

    public static void requireRole(User user, Role... allowed) throws AccessDeniedException {
        if (user == null) {
            throw new AccessDeniedException("You must be logged in to do that.");
        }
        if (!user.isActive()) {
            throw new AccessDeniedException("Your account is deactivated.");
        }
        for (Role r : allowed) {
            if (user.getRole() == r) {
                return;
            }
        }
        throw new AccessDeniedException("Access denied: this action is not available to "
                + user.getRole().getLabel().toLowerCase() + " accounts.");
    }

    public static Teacher requireTeacher(User user) throws AccessDeniedException {
        requireRole(user, Role.TEACHER);
        return (Teacher) user;
    }

    public static Student requireStudent(User user) throws AccessDeniedException {
        requireRole(user, Role.STUDENT);
        return (Student) user;
    }
}
