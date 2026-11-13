@echo off
rem Build and run the Quiz & Examination Management System (Windows).
rem   run.bat           compile and start the application (data in .\data)
rem   run.bat --demo    start and create demo records if the data folder is empty
rem   run.bat test      compile and run the automated tests
rem   run.bat web       start the website at http://localhost:8080 (add --demo, --port N)
rem From PowerShell (e.g. the VS Code terminal) type .\run.bat instead of run.bat
setlocal
cd /d "%~dp0"
if exist out rmdir /s /q out
mkdir out
type nul > out\sources.txt
rem Each path is quoted (with forward slashes) so folders with spaces, like "TUF A15", work.
for /r src %%f in (*.java) do call :addsource "%%f"
if "%1"=="test" for /r test %%f in (*.java) do call :addsource "%%f"
javac --release 17 -encoding UTF-8 -d out @out\sources.txt || exit /b 1
if "%1"=="test" (
  java -cp out tests.TestRunner
) else if "%1"=="web" (
  java -cp out web.WebMain %2 %3 %4 %5 %6 %7 %8 %9
) else (
  java -cp out main.Main %*
)
exit /b %errorlevel%

:addsource
set "p=%~1"
>>out\sources.txt echo "%p:\=/%"
exit /b 0
