package services;

import exceptions.QuizSystemException;
import models.Admin;
import models.Difficulty;
import models.EnumerationQuestion;
import models.EssayQuestion;
import models.Exam;
import models.FillInTheBlankQuestion;
import models.IdentificationQuestion;
import models.MatchingQuestion;
import models.MultipleChoiceQuestion;
import models.MultipleSelectQuestion;
import models.NumericalQuestion;
import models.Question;
import models.Role;
import models.SchoolClass;
import models.Student;
import models.Subject;
import models.Teacher;
import models.TimerSettings;
import models.TrueFalseQuestion;
import models.User;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Optional demonstration data (run with {@code --demo}). Everything is created through the
 * normal services and saved to the data files - it is NOT a replacement for persistence, and it
 * only runs when the data folder holds no subjects yet.
 */
public final class DemoDataSeeder {
    public static final String PASSWORD_ADMIN = "Admin123";
    public static final String PASSWORD_TEACHER = "Teacher123";
    public static final String PASSWORD_STUDENT = "Student123";

    private DemoDataSeeder() {
    }

    public static String seed(ServiceRegistry s) throws QuizSystemException {
        if (!s.store().getSubjects().isEmpty() || s.store().userCount() > 1) {
            return "Demo data skipped: the data folder already contains records.";
        }
        Admin admin;
        if (s.auth().needsInitialSetup()) {
            admin = s.auth().createInitialAdmin("admin", PASSWORD_ADMIN, "System Administrator", "admin@school.edu");
        } else {
            User u = s.store().getUsers().iterator().next();
            if (u.getRole() != Role.ADMIN) {
                return "Demo data skipped: the only existing account is not an administrator.";
            }
            admin = (Admin) u;
        }
        Teacher teacher = s.users().createTeacher(admin, "teacher1", PASSWORD_TEACHER, "Maria Santos",
                "maria.santos@school.edu", "Computer Science");
        Student s1 = s.users().createStudent(admin, "student1", PASSWORD_STUDENT, "Juan Dela Cruz", "", "2024-0001", "2nd Year");
        Student s2 = s.users().createStudent(admin, "student2", PASSWORD_STUDENT, "Ana Reyes", "", "2024-0002", "2nd Year");
        Student s3 = s.users().createStudent(admin, "student3", PASSWORD_STUDENT, "Leo Garcia", "", "2024-0003", "2nd Year");

        Subject oop = s.subjects().createSubject(admin, "CS201", "Object-Oriented Programming", "Java OOP fundamentals");
        s.subjects().assignTeacher(admin, oop.getSubjectId(), teacher.getUserId());
        SchoolClass section = s.subjects().createClass(admin, "BSIT 2-A");
        for (Student st : List.of(s1, s2, s3)) {
            s.subjects().addStudentToClass(admin, section.getClassId(), st.getUserId());
        }
        s.subjects().enrollClassInSubject(admin, section.getClassId(), oop.getSubjectId());

        String sub = oop.getSubjectId();
        String tid = teacher.getUserId();
        QuestionBankService qb = s.questions();
        List<Question> qs = new ArrayList<>();
        qs.add(new MultipleChoiceQuestion(qb.nextQuestionId(), tid, sub, "Which keyword creates a subclass in Java?", 1,
                "Inheritance", Difficulty.EASY, "'extends' declares the parent class.",
                List.of("implements", "extends", "super", "instanceof"), 1));
        qs.add(new MultipleSelectQuestion(qb.nextQuestionId(), tid, sub, "Which of these are OOP principles?", 2,
                "Concepts", Difficulty.MEDIUM, "", List.of("Encapsulation", "Compilation", "Polymorphism", "Inheritance"),
                List.of(0, 2, 3), true));
        qs.add(new TrueFalseQuestion(qb.nextQuestionId(), tid, sub, "An abstract class can be instantiated directly.", 1,
                "Abstraction", Difficulty.EASY, "Abstract classes cannot be instantiated.", false));
        qs.add(new IdentificationQuestion(qb.nextQuestionId(), tid, sub,
                "What is the name of the method every Java application starts from?", 1, "Basics", Difficulty.EASY, "",
                List.of("main", "main method"), false));
        qs.add(new FillInTheBlankQuestion(qb.nextQuestionId(), tid, sub,
                "A class that cannot be extended is declared ___; a method that must be overridden is declared ___.", 2,
                "Keywords", Difficulty.MEDIUM, "", List.of(List.of("final"), List.of("abstract"))));
        qs.add(new EnumerationQuestion(qb.nextQuestionId(), tid, sub, "List the four access levels in Java.", 4,
                "Encapsulation", Difficulty.MEDIUM, "", List.of("public", "protected", "default", "private"), false, false));
        qs.add(new EssayQuestion(qb.nextQuestionId(), tid, sub, "Explain polymorphism with an example.", 5,
                "Polymorphism", Difficulty.HARD, "", 30, "2 pts definition, 2 pts example, 1 pt clarity"));
        qs.add(new MatchingQuestion(qb.nextQuestionId(), tid, sub, "Match each collection to its description.", 3,
                "Collections", Difficulty.MEDIUM, "",
                List.of("ArrayList", "HashMap", "HashSet"),
                List.of("Key-value pairs", "No duplicates", "Ordered, resizable list", "First-in first-out queue"),
                Map.of(0, 2, 1, 0, 2, 1)));
        qs.add(new NumericalQuestion(qb.nextQuestionId(), tid, sub, "What is the value of 7 / 2.0 in Java?", 1,
                "Basics", Difficulty.EASY, "", 3.5, 0));
        for (Question q : qs) {
            qb.addQuestion(teacher, q);
        }

        Exam exam = s.exams().createExam(teacher, sub, "OOP Quiz 1",
                "Answer all questions. You may navigate freely and change answers until you submit.");
        for (Question q : qs) {
            s.exams().addQuestion(teacher, exam.getExamId(), q.getQuestionId());
        }
        s.exams().updateDetails(teacher, exam.getExamId(), exam.getTitle(), exam.getInstructions(), 60, 2,
                true, true, false);
        s.exams().setTimer(teacher, exam.getExamId(), new TimerSettings(true, Duration.ofMinutes(15), true, true));
        s.exams().assignClass(teacher, exam.getExamId(), section.getClassId());
        s.exams().publish(teacher, exam.getExamId());

        return "Demo data created and saved.\n"
                + "  Administrator : admin / " + PASSWORD_ADMIN + "\n"
                + "  Teacher       : teacher1 / " + PASSWORD_TEACHER + "\n"
                + "  Students      : student1, student2, student3 / " + PASSWORD_STUDENT + "\n"
                + "  Exam          : 'OOP Quiz 1' (9 question types, 15-minute timer, 2 attempts) is open now.";
    }
}
