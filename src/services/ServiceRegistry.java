package services;

import data.DataStore;
import data.FileManager;
import exceptions.DataPersistenceException;

import java.nio.file.Path;
import java.time.Clock;

/** Creates and wires every service around one DataStore and one Clock. */
public class ServiceRegistry {
    private final DataStore store;
    private final Clock clock;
    private final AuthenticationService auth;
    private final UserService users;
    private final SubjectService subjects;
    private final QuestionBankService questions;
    private final ExamService exams;
    private final GradingService grading;
    private final ExamSessionService sessions;
    private final ReportService reports;

    public ServiceRegistry(DataStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
        this.auth = new AuthenticationService(store);
        this.users = new UserService(store, auth);
        this.subjects = new SubjectService(store);
        this.questions = new QuestionBankService(store);
        this.exams = new ExamService(store, clock);
        this.grading = new GradingService(store, clock, exams);
        this.sessions = new ExamSessionService(store, clock, exams, grading);
        this.reports = new ReportService(store, clock, exams, subjects);
    }

    /** Opens (or creates) the data folder and loads every record. */
    public static ServiceRegistry open(Path dataDir, Clock clock) throws DataPersistenceException {
        DataStore store = new DataStore(new FileManager(dataDir));
        store.loadAll();
        return new ServiceRegistry(store, clock);
    }

    public DataStore store() {
        return store;
    }

    public Clock clock() {
        return clock;
    }

    public AuthenticationService auth() {
        return auth;
    }

    public UserService users() {
        return users;
    }

    public SubjectService subjects() {
        return subjects;
    }

    public QuestionBankService questions() {
        return questions;
    }

    public ExamService exams() {
        return exams;
    }

    public GradingService grading() {
        return grading;
    }

    public ExamSessionService sessions() {
        return sessions;
    }

    public ReportService reports() {
        return reports;
    }
}
