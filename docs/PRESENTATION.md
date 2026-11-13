# Final Presentation Outline (about 15 minutes)

| # | Slide / segment | Presenter | Content |
|---|---|---|---|
| 1 | Title & team | All | Project name, members, roles (Member 1 users/auth, Member 2 exams/timer, Member 3 data/grading/reports) |
| 2 | Problem & scope | Member 1 | Three roles, console application, Java 17, no external libraries, file persistence |
| 3 | Architecture | Member 3 | Packages `models / services / data / ui / exceptions / util`; layering rule ui → services → data/models (diagram in `docs/DESIGN.md` §2) |
| 4 | OOP: inheritance & encapsulation | Member 1 | `User` → `Admin/Teacher/Student`; private fields, validating setters, no getter for the password hash |
| 5 | OOP: abstraction & polymorphism | Member 2 | Abstract `Question` → 9 types; `GradingService.autoGrade` loops over `Question` references and calls `evaluate()`; `RoleMenu` interface |
| 6 | Collections & exceptions | Member 3 | HashMap username index, LinkedHashMap answers, EnumMap menus; `QuizSystemException` + 9 custom exceptions |
| 7 | **Live demo 1 - teacher** | Member 2 | Log in as `teacher1`, add a question, create an exam with a 1-minute timer, assign, publish |
| 8 | **Live demo 2 - student** | Member 2 | Log in as `student1`, answer, flag, review, quit and resume (same deadline), let the timer auto-submit |
| 9 | **Live demo 3 - grading & reports** | Member 3 | Grade the essay, release, show the statistics report and ranking, export to a file |
| 10 | Persistence & recovery | Member 3 | Restart the app (data is still there); corrupt `users.dat` by hand and show recovery from the backup |
| 11 | Security | Member 1 | PBKDF2 hashing, lockout, role checks in the service layer, students only see their own released results |
| 12 | Testing | Member 3 | `./run.sh test` (83 automated tests, fake clock); manual end-to-end tests in `docs/TESTING.md` |
| 13 | Challenges & lessons | All | Timer in a console app, storing a copy of each question in published exams, keeping two threads from both submitting the same attempt |
| 14 | Q&A | All | |

**Before the demo:** delete `data/`, run `./run.sh --demo`, and practise the demo script once.
