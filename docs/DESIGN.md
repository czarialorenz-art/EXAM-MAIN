# Design Document — Java Quiz & Examination Management System

**Status: implemented.** This document describes the design as built. Section 12 lists where the final code differs from the original Phase 0 plan.
Target: Java 17+ · Console UI (Swing GUI is an optional bonus layer later) · Java serialization for persistence · No external libraries

---

## 1. Requirements Analysis

### 1.1 Main decisions

| Decision | Choice | Why |
|---|---|---|
| Interface | **Console menus first** | Every requirement can be met and tested in a console app. A Swing GUI can be added in week 2 on top of the same services if there is time. The services never print anything, so the GUI can reuse them unchanged. |
| Persistence | **Java serialization** (`.dat` files, one per record type) | `Question` has 9 subclasses with different fields. Serialization stores them as they are; CSV would need a custom format for each type. |
| Password storage | **PBKDF2-HMAC-SHA256 with a random salt per user** (`javax.crypto`, part of the JDK) | Meets §7 ("not plain text") without any library. |
| Time source | **`java.time.Clock`** passed into the services | Real clock in the app, fixed/fake clock in tests, so timer tests don't have to wait 5 real minutes. |
| First run | If `users.dat` doesn't exist, the app asks to **create the first Admin account**. | Avoids hardcoded sample records (§12). A separate optional `SeedData` command can create demo data, which is then saved through the normal persistence path. |
| Build | Plain `javac`/`java` (scripts provided) and the project also opens directly in IntelliJ/VS Code/NetBeans | No Maven/Gradle setup to fight with. |

### 1.2 Requirement → module map

| Req § | Module | Main classes | Owner |
|---|---|---|---|
| 3.1 Auth | Authentication | `AuthenticationService`, `PasswordHasher`, `Session` (logged-in user) | M1 |
| 3.2 Users | User management | `User`, `Admin`, `Teacher`, `Student`, `UserService` | M1 |
| 3.3 Subjects/Classes | Subject management | `Subject`, `SchoolClass`, `SubjectService` | M1 |
| 3.4 Question bank | Questions | `Question` (abstract) + 9 subclasses, `QuestionBankService` | M2 |
| 3.5 Exams | Exam management | `Exam`, `TimerSettings`, `ExamStatus`, `ExamService` | M2 |
| 3.6 / 3.7 / 3.8 | Taking an exam, timer, autosave | `ExamSession`, `StudentAnswer`, `ExamSessionService`, `ExamTimer`, `ExamInterface` | M2 (UI + timer) / M3 (autosave) |
| 3.9 Grading | Grading | `GradingService`, `Result`, `QuestionScore`, `GradeOutcome` | M3 |
| 3.10 Reports | Reports | `ReportService`, `ExamStatistics` | M3 |
| 3.11 Search/sort | Search & sort | `search*`/`filter*` methods and `Comparator` constants in each service | M3 |
| 3.12 Files | Persistence | `FileManager`, `DataStore` | M3 |
| 3.13 Exceptions | Exceptions | `exceptions` package | M1 (base) + each owner |

---

## 2. Package Structure

```
JavaQuizExamSystem/
├── src/
│   ├── main/         Main.java                      – entry point only (≈20 lines)
│   ├── models/       User, Admin, Teacher, Student, Role,
│   │                 Subject, SchoolClass,
│   │                 Question (abstract), ChoiceQuestion (abstract),
│   │                 MultipleChoiceQuestion, MultipleSelectQuestion, TrueFalseQuestion,
│   │                 IdentificationQuestion, FillInTheBlankQuestion, EnumerationQuestion,
│   │                 EssayQuestion, MatchingQuestion, NumericalQuestion,
│   │                 QuestionType, Difficulty, GradeOutcome,
│   │                 Exam, ExamStatus, TimerSettings,
│   │                 ExamSession, SessionState, SubmissionType, StudentAnswer,
│   │                 Result, ResultStatus, QuestionScore
│   ├── services/     AccessControl, AuthenticationService, UserService, SubjectService,
│   │                 QuestionBankService, ExamService, ExamSessionService,
│   │                 GradingService, ReportService, ServiceRegistry, DemoDataSeeder
│   ├── data/         FileManager, DataStore, IdGenerator
│   ├── exceptions/   QuizSystemException (base) + 9 subclasses (see §7)
│   ├── util/         PasswordHasher, InputValidator, TimeFormatter, TextUtil
│   └── ui/           ConsoleMenu, RoleMenu (interface), ScreenBase, BaseMenu,
│                     AdminMenu, TeacherMenu, StudentMenu, ReportScreens, QuestionForm,
│                     ExamInterface, ExamTimer, InputReader, TablePrinter, InputClosedException
├── test/tests/       TestRunner + 7 test suites (plain Java, no JUnit required)
├── data/             runtime .dat files (git-ignored)
├── docs/             DESIGN.md, uml/, TESTING.md
├── run.sh / run.bat  compile + run scripts
└── README.md
```

**Layering rule:** `ui → services → data/models`. Models never call services; services never read from `System.in` or print to `System.out`. Each service checks permissions itself (it receives the logged-in `User`), so the role checks don't depend on the menus being correct.

---

## 3. Class Design

### 3.1 Users (inheritance + encapsulation) — Member 1

```java
public abstract class User implements Serializable {
    private final String userId;        // e.g. "STU-0001", fixed once created
    private String username;            // unique, 4–20 chars [a-zA-Z0-9_]
    private String passwordHash;        // PBKDF2 output, Base64; never exposed by a getter
    private String salt;
    private String fullName;
    private String email;
    private boolean active;

    public abstract Role getRole();                 // overridden by each subclass
    public abstract String getDisplaySummary();     // polymorphic profile line
    public boolean verifyPassword(String plain)     // delegates to PasswordHasher
    public void changePassword(String oldPlain, String newPlain) throws ValidationException
    // setters check their input, e.g. setFullName rejects blank values
}
public class Admin   extends User { }                       // role ADMIN
public class Teacher extends User { private String department; }
public class Student extends User { private String studentNumber; private String yearLevel; }
```

Which subjects a teacher teaches and which classes a student is in are stored only once: on `Subject` (`teacherId`, `enrolledStudentIds`) and on `SchoolClass` (`studentIds`). Their getters return `Collections.unmodifiableSet(...)`, so membership can only change through the services.

More polymorphism: `getRoleDetailLabel()`, `getRoleDetail()`, `setRoleDetail()` and `getSearchText()` are overridden by `Teacher` (department) and `Student` (year level, student number). Admin editing and user search work through `User` references without type checks.

### 3.2 Subjects and classes

```java
public class Subject implements Serializable { String subjectId, code, name, description; String teacherId; Set<String> enrolledStudentIds; }
public class SchoolClass implements Serializable { String classId, name /* "BSIT 2-A" */; Set<String> studentIds; }
```

An exam can be assigned to individual students, to whole classes, or to everyone enrolled in its subject.

### 3.3 Question hierarchy (abstraction + polymorphism) — Member 2

```java
public abstract class Question implements Serializable {
    private final String questionId;
    private String text, subjectId, category, explanation;
    private double points;                 // > 0
    private Difficulty difficulty;         // EASY, MEDIUM, HARD
    private final String createdByTeacherId;

    public abstract QuestionType getType();
    public abstract String render(List<Integer> choiceOrder);   // text shown to the student
    public abstract GradeOutcome grade(StudentAnswer answer);   // automatic grading
    public abstract boolean requiresManualGrading();
    public abstract String describeAnswerKey();                 // shown to teachers only
    public abstract Question copy();                            // copy stored in the exam (see 3.4)
    public void validate() throws InvalidQuestionException { /* common checks */ }  // subclasses add their own checks, then call super
}
```

| Class | Extra fields | `grade()` behaviour | Auto? |
|---|---|---|---|
| `ChoiceQuestion` *(abstract)* | `List<String> choices` (2–6) | — | — |
| `MultipleChoiceQuestion` | `int correctIndex` | Full points if the selected index matches | ✅ |
| `MultipleSelectQuestion` | `Set<Integer> correctIndexes`, `boolean partialCredit` | Partial credit: (right picks − wrong picks) / number correct, never below 0 | ✅ |
| `TrueFalseQuestion` | `boolean correct` | Exact match | ✅ |
| `IdentificationQuestion` | `List<String> acceptedAnswers`, `boolean caseSensitive` | Trimmed and normalized, then compared with any accepted answer | ✅ |
| `FillInTheBlankQuestion` | `List<List<String>> acceptedPerBlank` (text uses `___`) | Points split evenly across the blanks | ✅ |
| `EnumerationQuestion` | `List<String> expectedItems`, `boolean orderMatters`, `boolean manual` | Each correct item earns its share; if `manual`, goes to the teacher instead | ✅ / ✋ |
| `EssayQuestion` | `int minWords`, `String rubric` | Returns `GradeOutcome.pending()` | ✋ |
| `MatchingQuestion` | `List<String> left, right`, `Map<Integer,Integer> key` | Points split per correct pair | ✅ |
| `NumericalQuestion` | `double answer`, `double tolerance` | Correct if \|given − answer\| ≤ tolerance | ✅ |

`GradeOutcome` is a small value class: `(double earned, double max, boolean pendingManual)`.

**Polymorphism in practice:** `GradingService` loops over `List<Question>` and calls `q.grade(answer)` without checking types or using `instanceof`. `ExamInterface` calls `q.render(order)` the same way. These two loops are the main code to show for §4.4.

### 3.4 Exams

```java
public class Exam implements Serializable {
    private final String examId;
    private String title, instructions, subjectId, teacherId;
    private List<Question> questions;           // copied from the bank when the exam is published
    private List<String> draftQuestionIds;      // bank question IDs while the exam is a draft
    private double passingPercentage;           // 0–100
    private TimerSettings timer;
    private LocalDateTime opensAt, closesAt;    // schedule (may be null = no limit)
    private int maxAttempts;                    // ≥ 1
    private boolean shuffleQuestions, shuffleChoices, showResultsImmediately;
    private Set<String> assignedStudentIds, assignedClassIds;
    private ExamStatus status;                  // stored status
    public ExamStatus getEffectiveStatus(Clock clock) // works out ACTIVE/COMPLETED from the schedule
    public double getTotalPoints()
}
public class TimerSettings implements Serializable {
    boolean enabled; Duration duration; boolean autoSubmit; boolean allowPause;
    // validate(): 1 minute ≤ duration ≤ 24 hours → otherwise InvalidExamException
}
```

**Why exams store copies of the questions:** when the teacher publishes an exam, its questions are copied out of the bank. If the teacher later edits a bank question, exams that are already running or graded keep the original version. This protects results (§7) and stops grades from changing after the fact.

**Status lifecycle**

```
DRAFT ──publish──▶ PUBLISHED ──(opensAt reached)──▶ ACTIVE ──(closesAt reached / teacher closes)──▶ COMPLETED ──archive──▶ ARCHIVED
  ▲  (only DRAFT can be edited)       └──────── teacher may "unpublish" back to DRAFT only if nobody has started it
```
`PUBLISHED`, `ACTIVE` and `COMPLETED` are worked out from the stored status plus the clock, so the status is always correct without a background job updating it.

---

## 4. Exam Sessions and the Timer (§3.6–3.8)

### 4.1 `ExamSession` — the authoritative record of one attempt

```java
public class ExamSession implements Serializable {
    private final String sessionId, examId, studentId;
    private final int attemptNumber;
    private final Instant startedAt;
    private Instant deadline;                   // null if no time limit; set ONCE at start
    private Duration totalPaused;               // only used when pausing is allowed
    private Instant pausedAt;                   // non-null while paused
    private final List<Integer> questionOrder;  // fixed at start (shuffle applied once)
    private final Map<String, List<Integer>> choiceOrders;
    private final LinkedHashMap<String, StudentAnswer> answers;   // questionId → answer
    private SessionState state;                 // IN_PROGRESS, PAUSED, SUBMITTED, EXPIRED
    private Instant submittedAt;
    private SubmissionType submissionType;      // MANUAL, AUTO
}
public class StudentAnswer implements Serializable {
    String questionId; List<String> values; boolean markedForReview; Instant lastSavedAt; int revision;
}
```

### 4.2 Timer rules

1. **The deadline is set once.** When the attempt starts:
   `deadline = min(startedAt + timer.duration, exam.closesAt)`.
   It is saved to `sessions.dat` straight away.
2. **The stored deadline decides, not the on-screen countdown.** Every `saveAnswer()` and `submit()` call in `ExamSessionService` first checks `clock.instant()` against `session.getDeadline()`. The countdown on screen is only for the student to see.
3. **Moving between questions doesn't touch the deadline.** The interface reads the remaining time as `deadline − now` every time it redraws. No timer state lives in the UI, so there is nothing to reset.
4. **Reopening an attempt keeps the original deadline.** If the student closes the app or it crashes, on their next login the service finds the `IN_PROGRESS` session and loads it with its saved deadline. If the deadline has passed while they were away:
   - `autoSubmit = true` → submitted automatically with the saved answers, `submissionType = AUTO`.
   - `autoSubmit = false` → state becomes `EXPIRED`. Trying to submit throws `ExamTimeExpiredException`. The answers saved before the deadline are kept and the teacher can see them. The attempt still counts toward `maxAttempts`.
5. **Pausing (only if `allowPause`).** `pause()` saves `pausedAt`. `resume()` adds `now − pausedAt` to the deadline. Students cannot pause past `exam.closesAt`. If pausing is not allowed, the time keeps running while the app is closed (rule 4).
6. **Submitting twice is prevented in three places:**
   (a) `submit()` is `synchronized` and throws `DuplicateSubmissionException` if the state is already `SUBMITTED`/`EXPIRED`;
   (b) the background auto-submit and a manual submit go through the same method, so only one can win;
   (c) `GradingService` refuses to create a second `Result` for the same `sessionId`.
7. **Timestamps:** `startedAt` and `submittedAt` are `Instant` (UTC), shown in the computer's local time zone. `submissionType` is shown on the student's confirmation screen and on every results screen.

### 4.3 Timer in a console app (`ui.ExamTimer`)

A console can't redraw a countdown while it waits for typing, so:

- **Every exam screen starts with a status line:** `⏱ 00:42:17 left | Q 3/20 | Answered 2 | Flagged 1`.
- **A background `ScheduledExecutorService` thread** checks every second. It:
  - prints a warning once each at 10 min, 5 min and 1 min left (it remembers which warnings it has printed);
  - when the deadline passes and `autoSubmit` is on, calls `ExamSessionService.submit(session, AUTO)` and prints *"Time is up — your answers were submitted automatically."* The next thing the student types just shows the confirmation screen.
- Menu option `T` shows the exact remaining time whenever the student wants it.
- The thread is a daemon thread and is shut down in `finally` when the student leaves the exam, so it can't keep running in the background.

(A Swing GUI would use a `javax.swing.Timer` label instead; the service code stays the same.)

### 4.4 Autosave (§3.8)

- `ExamSessionService.saveAnswer(...)` updates the `StudentAnswer`, increases its `revision` and immediately saves `sessions.dat` (writing to a temporary file first, see §6). This happens on every answer change and every move to another question.
- **Preventing accidental overwrites:** if a question already has an answer, the interface asks *"Replace your previous answer 'B'? (y/n)"*. Answers can't be changed after the deadline or after submitting (`ExamTimeExpiredException` / `InvalidExamException`).

### 4.5 Student exam navigation

```
[N]ext  [P]revious  [G]o to #  [A]nswer  [F]lag/unflag  [R]eview list  [T]ime  [S]ubmit  [Q]uit (resume later)
```
The review list shows every question as ✔ answered, ○ unanswered or ⚑ flagged. Before submitting, the student sees *"You have 3 unanswered and 1 flagged question(s). Submit? (type YES)"*.

**Who can open an exam** (checked by `ExamService.assertCanStart`): the student is assigned (directly, by class, or by subject enrollment) **and** the effective status is `ACTIVE` **and** they have attempts left **and** they don't already have an attempt in progress (if they do, it is resumed instead). Otherwise → `AccessDeniedException` / `InvalidExamException` with a clear message.

---

## 5. Grading and Results (§3.9–3.10) — Member 3

```java
public class Result implements Serializable {
    String resultId, sessionId, examId, studentId;
    Map<String, QuestionScore> scores;      // questionId → (earned, max, feedback, pendingManual, gradedBy)
    double totalScore, maxScore, percentage;
    boolean passed;
    ResultStatus status;                    // PENDING_MANUAL, FINAL
    boolean released;                       // students see only released FINAL results
    String overallFeedback;
}
```

- `GradingService.autoGrade(session)` runs right after submitting: it calls `grade()` on every question (polymorphism). If any `QuestionScore.pendingManual` is true → status `PENDING_MANUAL`, otherwise `FINAL`.
- `GradingService.gradeManually(teacher, resultId, questionId, points, feedback)` checks `0 ≤ points ≤ max` and that the teacher owns the exam, then recalculates the totals. A result only becomes `FINAL` once **nothing is pending** (§3.9 last line). `releaseResults(examId)` refuses to release a result that is still pending.
- **What students can see:** `ReportService.getResultsForStudent(student)` only ever filters by `student.getUserId()`, and it is the only results method the student menu calls.
- Teachers' answer keys are never shown to students: `describeAnswerKey()` is only called from teacher screens, and the student screens only call `render()`.

**Reports (`ReportService`)**: class average, highest and lowest (with student names), pass and fail rates, completion (assigned vs. submitted vs. not started), per-question difficulty (% correct), and a student's attempt history. Each report can also be exported to `data/reports/<name>_<timestamp>.txt`.

---

## 6. Collections and Persistence Strategy

### 6.1 In-memory data (`DataStore`)

| Data | Collection | Why |
|---|---|---|
| Users by ID | `HashMap<String, User>` | Fast lookup by ID |
| Users by username | `HashMap<String, String>` (lowercase username → ID) | Fast login + duplicate check |
| Subjects, classes | `HashMap<String, Subject/SchoolClass>` | Lookup by ID |
| Question bank | `ArrayList<Question>` + `HashMap` index | Ordered listing + lookup |
| Exams | `ArrayList<Exam>` | Sorting by date/title |
| Sessions | `HashMap<String, ExamSession>` | Resume by ID |
| Results | `ArrayList<Result>` | Sorting/reports |
| Answers within a session | `LinkedHashMap<String, StudentAnswer>` | Keeps question order |
| Enrollments, assignments | `HashSet<String>` | No duplicate entries |
| Warnings already shown | `EnumSet` / `TreeSet<Integer>` | Each warning shown only once |

### 6.2 Files (`data/` folder)

| File | Contents |
|---|---|
| `users.dat` | `HashMap<String, User>` |
| `subjects.dat` | subjects + classes |
| `questions.dat` | question bank |
| `exams.dat` | exams |
| `sessions.dat` | exam sessions with answers (autosaved) |
| `results.dat` | results |
| `ids.dat` | `IdGenerator` counters |

**`FileManager` rules**

1. **Write safely:** write to `x.dat.tmp` → copy the current `x.dat` to `x.dat.bak` → `Files.move(tmp, dat, ATOMIC_MOVE, REPLACE_EXISTING)`. A crash halfway through never destroys the last good file.
2. **Load at startup:** if a file is missing, start with an empty collection and log *"No exams found, starting fresh"*. If a file is **corrupted** (`StreamCorruptedException`, `InvalidClassException`, `ClassCastException`), try `x.dat.bak`. If that fails too, move the bad file to `x.dat.corrupt-<timestamp>` (so nothing is deleted), start empty and warn the admin.
3. Every model class declares `private static final long serialVersionUID = 1L;`.
4. Every change made through a service is saved immediately (`dataStore.saveUsers()`, etc.). There is also a "Save all" when exiting.
5. File errors are wrapped in `DataPersistenceException`, and the UI shows a readable message instead of crashing.

---

## 7. Exceptions

```
QuizSystemException (checked, base)
├── AuthenticationException        – wrong username/password, inactive account
├── AccessDeniedException          – wrong role / not assigned / someone else's record
├── DuplicateUserException         – username or ID already exists
├── ValidationException            – bad user input (email, password rules, ranges)
├── InvalidQuestionException       – missing text, no correct answer, too few choices…
├── InvalidExamException           – bad duration, no questions, edit after publish, no attempts left
├── ExamTimeExpiredException       – save/submit after the deadline
├── DuplicateSubmissionException   – second submit on the same session
└── DataPersistenceException       – file read/write problems
```
The menus catch `QuizSystemException` in one place for each menu loop and print `✖ <message>`. Bad menu choices and non-numeric input are handled in `InputReader`, which asks again instead of throwing.

---

## 8. UML Class Diagram

The full diagram is in [`docs/uml/class-diagram.md`](uml/class-diagram.md) (Mermaid, which displays directly on GitHub).

---

## 9. Task Split

| | Member 1 — Core OOP and users | Member 2 — Exams and questions | Member 3 — Data, grading and reports |
|---|---|---|---|
| Models | `User`, `Admin`, `Teacher`, `Student`, `Role`, `Subject`, `SchoolClass` | `Question` hierarchy (11 classes), `Exam`, `TimerSettings`, `ExamSession`, `StudentAnswer` | `Result`, `QuestionScore`, `GradeOutcome` |
| Services | `AuthenticationService`, `UserService`, `SubjectService` | `QuestionBankService`, `ExamService`, `ExamSessionService` | `GradingService`, `ReportService`, `ServiceRegistry` |
| Data/Util | `PasswordHasher`, `InputValidator` | `TimeFormatter` | `FileManager`, `DataStore`, `IdGenerator` |
| Exceptions | base class + Auth/Access/Duplicate/Validation | InvalidQuestion, InvalidExam, TimeExpired, DuplicateSubmission | DataPersistence |
| UI | `ConsoleMenu`, `AdminMenu`, `InputReader`, `TablePrinter` | `TeacherMenu` (questions/exams), `ExamInterface`, `ExamTimer` | `StudentMenu` (results/history), teacher grading & report screens |
| Tests | auth, users | questions, exams, timer | persistence, grading, reports, full workflow |
| Docs | UML | README run instructions | TESTING.md, presentation outline |

**Git workflow:** `main` is protected → each member works on `feature/<member>-<topic>` branches → pull request → at least one other member reviews it → merge. Make small commits with clear messages (`feat(auth): add PBKDF2 hashing`). Everyone runs `./run.sh test` before opening a PR.

**Integration contract (agree on day 1):** Member 3 writes `DataStore` method signatures and the empty `models` classes on day 1, so M1 and M2 can code against them right away.

---

## 10. Two-Week Roadmap

| Day | M1 | M2 | M3 | Group milestone |
|---|---|---|---|---|
| 1 | Approve this design, set up repo + branch rules | — | Scaffold `DataStore`/`FileManager` interfaces | ✅ Design approved, repo ready |
| 2–3 | User hierarchy, `PasswordHasher`, `AuthenticationService`, login + role menus | `Question` + 9 subclasses with `validate()`/`grade()` | `FileManager` safe writing + corruption recovery, `IdGenerator` | Can log in, create an admin, data stays after restart |
| 4–5 | `UserService` CRUD + search, `SubjectService`, classes, enrollment | `QuestionBankService`, teacher question screens, `Exam` + `ExamService` (create/edit/publish/assign) | Save/load all record types, persistence tests | Teacher can build and publish an exam |
| 6–7 | Admin menu complete, password change | `ExamSessionService`, `ExamInterface`, `ExamTimer` | Autosave + resume | **Student can take an exam end to end** |
| 8–9 | Validation everywhere, exception messages | Timer edge cases (expiry, pause, resume) | `GradingService` auto-grading + `Result` | Exam submitted → auto-graded |
| 10–11 | Search/sort screens for users and subjects | Shuffling, preview, archive | Manual grading, release, `ReportService`, sorting | Reports work |
| 12 | Integration + bug fixes (all) | | | Feature freeze |
| 13 | UML final | Timer test log | TESTING.md with **actual** results | Docs done |
| 14 | Demo rehearsal, slides, check each member's commits | | | Submission |

**If time runs short, cut in this order:** Swing GUI → per-question difficulty report → report export to file → pausing. These are all bonus or "where supported" features.

---

## 11. Testing Plan (summary)

- `test/TestRunner.java` runs plain-Java test classes (no JUnit download needed) and prints PASS/FAIL for each case.
- Timer tests use a **fake `Clock`**: move it forward 31 minutes → check auto-submit, `ExamTimeExpiredException`, warnings shown once, and that the deadline is the same after save → reload.
- Persistence tests use a temporary data folder: save → new `DataStore` → load → compare. Then break the file on purpose and check that it recovers from `.bak`.
- `docs/TESTING.md` lists every case from §10 of the requirements, with *expected result*, *actual result*, *date*, *tester*. Only fill in "Actual" after running the test.

---

## 12. Where the Implementation Differs from the Phase 0 Plan

| Plan | As built | Why |
|---|---|---|
| `Teacher.subjectIds`, `Student.classIds` | Membership is stored only on `Subject` / `SchoolClass` | A single place to store membership means the two sides can never disagree. |
| Separate `SearchService` | Search, filter and sort methods live in the service that owns each record type (`UserService.search`, `ExamService.searchByTitle`, `QuestionBankService.searchByCategory`, `ReportService.filterResults`, …) | Keeps each service self-contained. |
| `DataStore` was built by M3 on day 1 | Same, plus `ServiceRegistry` to wire all services together, and `AccessControl` for role checks | `Main` stays about 50 lines; the role checks live in one place. |
| UI menus only | Added the `RoleMenu` interface, the `ScreenBase`/`BaseMenu` base classes, `QuestionForm` and `ReportScreens` | Removes repeated menu code; `ConsoleMenu` picks a `RoleMenu` from an `EnumMap<Role, RoleMenu>` (polymorphism). |
| — | `DemoDataSeeder` (`--demo` flag) | Creates presentation data **through the services**, which saves it to the data files. It only runs when the data folder has no subjects yet. |
| — | Login lockout after 5 failed attempts (cleared by an admin password reset) | Extra protection for §7. |
| — | Deserialization filter (`models.*;data.*;java.base/*;!*`) in `FileManager` | Only the app's own classes and JDK classes can be loaded from a data file. |

### Rules decided during implementation
- **Deleting users:** a user who owns exams or questions, or has exam attempts, cannot be deleted, because that would leave results pointing at nobody. The admin deactivates the account instead.
- **Released results are locked:** a teacher must withdraw the release before changing a score.
- **Auto-release** (exam setting): FINAL results are shown to students as soon as they exist. Otherwise the teacher releases them.
- **Unanswered questions** score 0, including essays. Only *answered* subjective questions wait for manual grading.
- **Auto-submission time:** an attempt auto-submitted late (for example, found after the app was restarted) is stamped with its **deadline**, not the moment it was found.
- **Closing an exam early** sets its closing time to now. Attempts in progress are then auto-submitted (or expire, if auto-submit is off) by the same deadline check.
