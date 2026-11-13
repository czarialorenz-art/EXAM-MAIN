package ui;

import exceptions.QuizSystemException;
import models.User;
import services.ServiceRegistry;

/** Parent of the three role dashboards; adds behaviour shared by all logged-in users. */
public abstract class BaseMenu extends ScreenBase implements RoleMenu {

    protected BaseMenu(ServiceRegistry services, InputReader in) {
        super(services, in);
    }

    protected void changePassword(User user) throws QuizSystemException {
        sub("Change password");
        String current = in.password("Current password: ");
        String fresh = in.password("New password (min 8 characters, letters and digits): ");
        String confirmation = in.password("Confirm new password: ");
        services.auth().changePassword(user, current, fresh, confirmation);
        success("Password changed.");
    }
}
