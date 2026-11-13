package ui;

import models.User;

/** A role-specific dashboard. ConsoleMenu picks the implementation from the user's role (polymorphism). */
public interface RoleMenu {
    void show(User user);
}
