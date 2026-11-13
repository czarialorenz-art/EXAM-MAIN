# UML Class Diagram

The diagrams below use Mermaid and display directly on GitHub. Fields and methods are trimmed to the important ones; see `docs/DESIGN.md` for full details. Classes marked `<<abstract>>` declare abstract methods (for example `Question.grade`, `User.getRole`) that each subclass overrides.

## 1. Users

```mermaid
classDiagram
    class User {
        <<abstract>>
        -String userId
        -String username
        -String passwordHash
        -String salt
        -String fullName
        -String email
        -boolean active
        +getRole() Role
        +getDisplaySummary() String
        +getRoleDetail() String
        +setRoleDetail(String) void
        +getSearchText() String
        +verifyPassword(String) boolean
        +changePassword(String, String) void
    }
    class Admin {
        +getRole() Role
    }
    class Teacher {
        -String department
        +getRole() Role
        +setRoleDetail(String) void
    }
    class Student {
        -String studentNumber
        -String yearLevel
        +getRole() Role
        +getSearchText() String
    }
    class Role {
        <<enumeration>>
        ADMIN
        TEACHER
        STUDENT
    }
    class Subject {
        -String subjectId
        -String code
        -String name
        -String teacherId
        -Set~String~ enrolledStudentIds
    }
    class SchoolClass {
        -String classId
        -String name
        -Set~String~ studentIds
    }
    User <|-- Admin
    User <|-- Teacher
    User <|-- Student
    User --> Role
    Teacher "1" --> "*" Subject : teaches
    Student "*" --> "*" Subject : enrolled in
    Student "*" --> "*" SchoolClass : member of
```

## 2. Questions (abstraction and polymorphism)

```mermaid
classDiagram
    class Question {
        <<abstract>>
        -String questionId
        -String text
        -double points
        -String subjectId
        -String category
        -Difficulty difficulty
        -String explanation
        +getType() QuestionType
        +render(List~Integer~) String
        +grade(StudentAnswer) GradeOutcome
        +requiresManualGrading() boolean
        +describeAnswerKey() String
        +copy() Question
        +validate() void
    }
    class ChoiceQuestion {
        <<abstract>>
        -List~String~ choices
    }
    class MultipleChoiceQuestion {
        -int correctIndex
    }
    class MultipleSelectQuestion {
        -Set~Integer~ correctIndexes
        -boolean partialCredit
    }
    class TrueFalseQuestion {
        -boolean correct
    }
    class IdentificationQuestion {
        -List~String~ acceptedAnswers
        -boolean caseSensitive
    }
    class FillInTheBlankQuestion {
        -List~BlankAnswers~ acceptedPerBlank
    }
    class EnumerationQuestion {
        -List~String~ expectedItems
        -boolean orderMatters
        -boolean manual
    }
    class EssayQuestion {
        -int minWords
        -String rubric
    }
    class MatchingQuestion {
        -List~String~ left
        -List~String~ right
        -Map~Integer,Integer~ key
    }
    class NumericalQuestion {
        -double answer
        -double tolerance
    }
    class GradeOutcome {
        -double earned
        -double max
        -boolean pendingManual
    }

    Question <|-- ChoiceQuestion
    ChoiceQuestion <|-- MultipleChoiceQuestion
    ChoiceQuestion <|-- MultipleSelectQuestion
    Question <|-- TrueFalseQuestion
    Question <|-- IdentificationQuestion
    Question <|-- FillInTheBlankQuestion
    Question <|-- EnumerationQuestion
    Question <|-- EssayQuestion
    Question <|-- MatchingQuestion
    Question <|-- NumericalQuestion
    Question ..> GradeOutcome : creates
```

## 3. Exams, sessions and results

```mermaid
classDiagram
    class Exam {
        -String examId
        -String title
        -String instructions
        -String subjectId
        -String teacherId
        -List~Question~ questions
        -double passingPercentage
        -LocalDateTime opensAt
        -LocalDateTime closesAt
        -int maxAttempts
        -boolean shuffleQuestions
        -boolean shuffleChoices
        -Set~String~ assignedStudentIds
        -Set~String~ assignedClassIds
        -ExamStatus status
        +getEffectiveStatus(Clock) ExamStatus
        +getTotalPoints() double
    }
    class TimerSettings {
        -boolean enabled
        -Duration duration
        -boolean autoSubmit
        -boolean allowPause
        +validate() void
    }
    class ExamStatus {
        <<enumeration>>
        DRAFT
        PUBLISHED
        ACTIVE
        COMPLETED
        ARCHIVED
    }
    class ExamSession {
        -String sessionId
        -String examId
        -String studentId
        -int attemptNumber
        -Instant startedAt
        -Instant deadline
        -List~Integer~ questionOrder
        -LinkedHashMap~String,StudentAnswer~ answers
        -SessionState state
        -Instant submittedAt
        -SubmissionType submissionType
        +remaining(Clock) Duration
        +isExpired(Clock) boolean
    }
    class StudentAnswer {
        -String questionId
        -List~String~ values
        -boolean markedForReview
        -Instant lastSavedAt
        -int revision
    }
    class Result {
        -String resultId
        -String sessionId
        -Map~String,QuestionScore~ scores
        -double totalScore
        -double percentage
        -boolean passed
        -ResultStatus status
        -boolean released
    }
    class QuestionScore {
        -double earned
        -double max
        -String feedback
        -boolean pendingManual
    }
    Exam *-- TimerSettings
    Exam --> ExamStatus
    Exam "1" o-- "*" Question : copies of bank questions
    Exam "1" --> "*" ExamSession : attempts
    ExamSession "1" *-- "*" StudentAnswer
    ExamSession "1" --> "0..1" Result
    Result "1" *-- "*" QuestionScore
```

## 4. Services, data and UI

```mermaid
classDiagram
    class DataStore {
        -HashMap~String,User~ usersById
        -HashMap~String,String~ idByUsername
        -ArrayList~Question~ questionBank
        -ArrayList~Exam~ exams
        -HashMap~String,ExamSession~ sessions
        -ArrayList~Result~ results
        +loadAll() void
        +saveUsers() void
        +saveSessions() void
    }
    class FileManager {
        -Path dataDir
        +save(String, Serializable) void
        +load(String, Supplier~T~) T
    }
    class AuthenticationService {
        +login(String, String) User
        +changePassword(User, String, String) void
    }
    class UserService {
        +addUser(User, User) void
        +search(String) List~User~
    }
    class SubjectService {
        +createSubject()
        +enroll()
        +assignTeacher()
    }
    class QuestionBankService {
        +addQuestion(Teacher, Question) void
        +searchByCategory(String)
    }
    class ExamService {
        +createExam()
        +publish()
        +assign()
        +availableFor(Student)
    }
    class ExamSessionService {
        -Clock clock
        +start()
        +saveAnswer()
        +submit()
        +resumeOrExpire()
    }
    class GradingService {
        +autoGrade(ExamSession) Result
        +gradeManually()
        +release()
    }
    class ReportService {
        +examStatistics(String) ExamStatistics
        +resultsForStudent(Student)
    }
    class AccessControl {
        +requireRole(User, Role...) void
    }
    class ServiceRegistry {
        +open(Path, Clock) ServiceRegistry
    }
    class RoleMenu {
        <<interface>>
        +show(User) void
    }
    class ScreenBase {
        <<abstract>>
        #runMenu(String, String, MenuItem...) void
        #choose(String, List, Function) T
    }
    class BaseMenu {
        <<abstract>>
        #changePassword(User) void
    }
    class ConsoleMenu {
        -EnumMap~Role,RoleMenu~ menus
        +run() void
    }
    class AdminMenu
    class TeacherMenu
    class StudentMenu
    class ReportScreens
    class QuestionForm
    class ExamInterface
    class ExamTimer {
        -ScheduledExecutorService scheduler
        +start()
        +stop()
    }

    DataStore --> FileManager
    AuthenticationService --> DataStore
    UserService --> DataStore
    SubjectService --> DataStore
    QuestionBankService --> DataStore
    ExamService --> DataStore
    ExamSessionService --> DataStore
    ExamSessionService --> GradingService : on submit
    GradingService --> DataStore
    ReportService --> DataStore
    ScreenBase <|-- BaseMenu
    RoleMenu <|.. BaseMenu
    BaseMenu <|-- AdminMenu
    BaseMenu <|-- TeacherMenu
    BaseMenu <|-- StudentMenu
    ScreenBase <|-- ReportScreens
    ScreenBase <|-- QuestionForm
    ScreenBase <|-- ExamInterface
    ConsoleMenu --> AuthenticationService
    ConsoleMenu --> RoleMenu : dispatches by role
    AdminMenu --> ReportScreens
    TeacherMenu --> ReportScreens
    TeacherMenu --> QuestionForm
    ServiceRegistry --> DataStore
    AccessControl ..> User
    StudentMenu --> ExamInterface
    ExamInterface --> ExamTimer
    ExamInterface --> ExamSessionService
```
