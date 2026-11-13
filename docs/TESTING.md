# Testing Documentation

All results below were **actually recorded** on 2026-11-13 (OpenJDK 21, compiled with `--release 17`, Linux).
Re-run them at any time and update this file with your own date and results.

## 1. How to run the tests

```bash
./run.sh test          # Linux/macOS
run.bat test           # Windows
```

The tests are plain Java (`test/tests/`, no JUnit needed). Each suite uses a fresh temporary data folder and a
**fake clock** (`MutableClock`), so timer cases such as "30 minutes later" run instantly and give the same result every time.

## 2. Automated test results

**Result of the recorded run: 83 passed, 0 failed.**

### Authentication

| # | Test case | Result |
|---|---|---|
| 1 | valid login returns the user with the right role | PASS |
| 2 | login is case-insensitive for the username only | PASS |
| 3 | wrong password and unknown username give the same error | PASS |
| 4 | account is locked after 5 failed attempts, admin reset unlocks it | PASS |
| 5 | deactivated accounts cannot log in | PASS |
| 6 | passwords are hashed with a salt, never stored in plain text | PASS |
| 7 | weak passwords are rejected | PASS |
| 8 | password change requires the current password and matching confirmation | PASS |
| 9 | first-run admin setup is only possible once | PASS |

### Role-based access

| # | Test case | Result |
|---|---|---|
| 10 | students and teachers cannot use admin functions | PASS |
| 11 | students cannot create questions or exams | PASS |
| 12 | a teacher cannot manage another teacher's subject | PASS |

### User management

| # | Test case | Result |
|---|---|---|
| 13 | duplicate usernames are rejected (case-insensitive) | PASS |
| 14 | duplicate student numbers are rejected | PASS |
| 15 | invalid account data is rejected (username, email, blank name) | PASS |
| 16 | edit user updates fields and keeps the username index consistent | PASS |
| 17 | failed edit leaves the record unchanged | PASS |
| 18 | delete user without history; users with history must be deactivated | PASS |

### Question types and polymorphic grading

| # | Test case | Result |
|---|---|---|
| 19 | Multiple choice: correct letter scores, wrong letter scores 0 | PASS |
| 20 | Multiple choice with shuffled choices maps the displayed letter back | PASS |
| 21 | Multiple select: all-or-nothing and partial credit | PASS |
| 22 | True/False accepts T/F/true/false | PASS |
| 23 | Identification: accepted answers, case/space-insensitive by default | PASS |
| 24 | Fill in the blanks: per-blank partial scoring | PASS |
| 25 | Enumeration: any order, duplicates do not count twice, extras ignored | PASS |
| 26 | Essay is always pending manual grading when answered; unanswered scores 0 | PASS |
| 27 | Matching: pair syntax, letter sequence, partial credit, shuffled column B | PASS |
| 28 | Numerical: exact and tolerance | PASS |
| 29 | polymorphism: one loop grades every type through Question references | PASS |

### Question validation

| # | Test case | Result |
|---|---|---|
| 30 | invalid questions are rejected with clear messages | PASS |
| 31 | teacher can add, edit (copy) and delete bank questions | PASS |

### Examination management

| # | Test case | Result |
|---|---|---|
| 32 | create, add questions, publish -> exam is active and lists the questions | PASS |
| 33 | publish requires questions and at least one assignee | PASS |
| 34 | only drafts can be edited; adding the same question twice fails | PASS |
| 35 | invalid settings: duration, passing score, attempts, schedule | PASS |
| 36 | supported durations: 5, 10, 15, 30, 45 min, 1h, 1h30, 2h, custom | PASS |
| 37 | students cannot access exams that are not assigned to them | PASS |
| 38 | assignment by class and by individual student | PASS |
| 39 | draft and unpublished exams are hidden from students | PASS |
| 40 | schedule: not before opening, not after closing | PASS |
| 41 | maximum attempts is enforced | PASS |
| 42 | published exams keep their question snapshot when the bank changes | PASS |
| 43 | unpublish only before anyone starts; delete only without attempts; archive keeps data | PASS |
| 44 | shuffling is fixed per attempt (same order after resume) | PASS |

### Examination timer

| # | Test case | Result |
|---|---|---|
| 45 | deadline = start + configured duration; remaining counts down | PASS |
| 46 | custom duration 1h 30m 15s | PASS |
| 47 | navigating and answering never changes the deadline | PASS |
| 48 | closing and reopening the app preserves the original deadline and answers | PASS |
| 49 | answering after the deadline throws ExamTimeExpiredException | PASS |
| 50 | auto-submit at expiry submits saved answers, marked AUTOMATIC, timestamped at the deadline | PASS |
| 51 | manual submission is marked MANUAL and duplicate submissions are blocked | PASS |
| 52 | submitting late is rejected; with auto-submit off the attempt expires | PASS |
| 53 | attempt that expired while the app was closed is auto-submitted at start-up | PASS |
| 54 | pause freezes the countdown and resume extends the deadline (only if allowed) | PASS |
| 55 | deadline never goes past the exam closing time | PASS |
| 56 | teacher closing the exam early finishes attempts in progress | PASS |
| 57 | console timer prints each 10/5/1 minute warning once and auto-submits | PASS |

### Grading and results

| # | Test case | Result |
|---|---|---|
| 58 | objective grading: total, percentage and pass/fail | PASS |
| 59 | exactly the passing percentage passes | PASS |
| 60 | essays keep the result PENDING until manually graded; cannot release while pending | PASS |
| 61 | released results are locked until the release is withdrawn | PASS |
| 62 | only the owning teacher can grade | PASS |
| 63 | result visibility: students see only their own released results | PASS |
| 64 | auto-release publishes final results immediately | PASS |
| 65 | unanswered questions score zero | PASS |

### File persistence

| # | Test case | Result |
|---|---|---|
| 66 | all record types survive closing and reopening the application | PASS |
| 67 | new IDs continue after a restart (no duplicates) | PASS |
| 68 | missing data folder/files start empty without errors | PASS |
| 69 | corrupted file is quarantined and recovered from its backup | PASS |
| 70 | corrupted file without backup starts empty and warns, other files still load | PASS |
| 71 | saving leaves no temp file and keeps a .bak copy of the previous version | PASS |
| 72 | a file containing an unexpected type is treated as corrupted | PASS |
| 73 | backup and text report export write files | PASS |

### Search and sorting

| # | Test case | Result |
|---|---|---|
| 74 | search users by name, username, ID and student number | PASS |
| 75 | users are sorted by role then name | PASS |
| 76 | search exams by title and sort by date/title | PASS |
| 77 | search questions by category and keyword | PASS |
| 78 | results ranking is sorted from highest to lowest | PASS |
| 79 | filter results by subject, class and exam | PASS |

### Reports

| # | Test case | Result |
|---|---|---|
| 80 | exam statistics: average, highest, lowest, pass and fail rates, completion | PASS |
| 81 | statistics use each student's best attempt | PASS |
| 82 | class performance report | PASS |
| 83 | completion counts not-started and in-progress attempts | PASS |

## 3. Manual end-to-end tests (real console, scripted input)

These were run by piping a typed script into `java -cp out main.Main`, then reading the console output.

| # | Scenario | Steps | Expected | Actual result |
|---|---|---|---|---|
| M1 | First run | Start with an empty data folder | Asked to create the first administrator | PASS: "FIRST-TIME SETUP" shown, admin created |
| M2 | Admin creates records | Add teacher and student, add a student with invalid data, add subject CS101, assign teacher, create class, add student to class, enrol class | Valid records saved; invalid student rejected with a message | PASS: `[OK]` for each valid step; `[ERROR] Username must be 4-20 characters…` for the bad record, and the app kept running |
| M3 | Teacher builds an exam | Add MC, Essay, Matching and Fill-in-the-blank questions; create exam with a 5-minute timer; add all questions; assign whole subject; preview; publish | Exam published with 4 questions / 10 points | PASS: "4 question(s) added. Total points: 10", "Published." |
| M4 | Student takes the exam | Open the exam, read instructions, answer all 4 (essay over several lines), review, submit with SUBMIT | Answers saved, confirmation with MANUAL submission | PASS: "Answer saved." ×4, confirmation "Submission type : MANUAL" |
| M5 | Result hidden before release | Student opens My results | Score hidden | PASS: "Awaiting grading" shown, no score |
| M6 | Manual grading | Teacher grades the essay 4/5 with feedback, releases results | Result becomes Final 9/10 (90%) | PASS: "Result is now Final: 9/10 (90.00%)", "1 result(s) released." |
| M7 | Student views the released result | My results → details | Score, per-question points, feedback, explanation | PASS: "Score: 9 / 10 (90.00%) - PASSED", feedback "Nice example" shown |
| M8 | Reports | Teacher: statistics report + export; ranking. Admin: system summary, data status | Correct numbers; report file written | PASS: average/highest/lowest 90%, pass rate 100%, file written to `data/reports/` |
| M9 | Persistence across restart | Start the app again (new process) on the same data folder; student opens history and results | All records still there | PASS: both attempts (Manual 90%, Automatic) and the released result were listed after restarting twice |
| M10 | **Live timer auto-submit** | Teacher creates a 1-minute exam; student answers one question and then stops typing for 80 s | 1-minute warning, then automatic submission at the deadline | PASS: `*** WARNING: 1 minute(s) remaining ***`, `*** TIME IS UP ***`, Started 14:43:03 → Submitted 14:44:03, "Submission type : AUTOMATIC" |
| M11 | Demo data | `./run.sh --demo` on an empty folder; student opens OOP Quiz 1 | 9 question types displayed, shuffled; navigation, flag and review work | PASS |

## 4. Requirement coverage (§10 of the project requirements)

| Requirement area | Covered by |
|---|---|
| Authentication: valid/invalid login, role access, duplicates, password validation | *Authentication* (tests 1-9), *Role-based access* (10-12), M1, M2 |
| User management: add, edit, delete, search, invalid input | *User management* (13-18), *Search and sorting*, M2 |
| Question management: each type, edit, delete, validation, polymorphism | *Question types and polymorphic grading*, *Question validation*, M3 |
| Examination: create, add questions, assign, durations, start/submit, maximum attempts | *Examination management*, M3, M4 |
| Timer: duration, countdown, expiry, auto-submit, duplicate prevention, navigation, saved deadline | *Examination timer*, M10 |
| Grading and results: objective, manual, calculations, pass/fail, visibility | *Grading and results*, M5-M7 |
| File persistence: save, reopen, missing and corrupted files | *File persistence*, M9 |
| Search and reports: accuracy, sort order, calculations | *Search and sorting*, *Reports*, M8 |

## 5. Known limitations (not defects)

- **Console countdown:** a console program can't redraw the screen while it waits for typing. The remaining time is shown each time the screen refreshes and with the `T` command; warnings and the time-up message are printed by the background timer thread. The stored deadline is always what decides.
- **Hidden passwords:** passwords are hidden only in a real terminal. IDE consoles (no `System.console()`) show them as you type.
- **No GUI:** the optional Swing/JavaFX bonus was not built; all functions are available in the console menus.
