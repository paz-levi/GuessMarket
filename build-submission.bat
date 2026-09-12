@echo off
setlocal
cd /d "%~dp0"

rem Assembles the actual two-artifact submission structure (Ex3 Stage 5) out of what build.bat/build-server.bat
rem already produced in dist\ -- this script does NOT compile anything itself, it only copies. Run build.bat THEN
rem build-server.bat first (in that order -- build.bat wipes dist\ wholesale, so it must run before
rem build-server.bat's own dist\GuessMarket.war would otherwise be deleted by a later build.bat run).
rem
rem Result: dist\submission\GuessMarket.war (deploy this to Tomcat's webapps\) and dist\submission\client\ (a
rem self-contained folder -- run-client.bat + the four jars its manifest chain needs + the whole javafx-sdk\ --
rem that must work when copied anywhere, on a machine with no JavaFX pre-installed, same standard as the repo
rem root's own existing run.bat/run-client.bat).

set "MISSING="
if not exist dist\GuessMarket.war set "MISSING=1"
if not exist dist\client.jar set "MISSING=1"
if not exist dist\gui.jar set "MISSING=1"
if not exist dist\engine.jar set "MISSING=1"
if not exist dist\gson-2.11.0.jar set "MISSING=1"
if defined MISSING (
    echo ERROR: dist\ is missing one or more required files.
    echo Run build.bat THEN build-server.bat first ^(in that order -- build.bat clears dist\ wholesale^), then retry.
    exit /b 1
)

echo Cleaning previous submission output...
if exist dist\submission rmdir /s /q dist\submission
mkdir dist\submission
mkdir dist\submission\client

echo Copying GuessMarket.war...
copy /y dist\GuessMarket.war dist\submission\ >nul
if errorlevel 1 goto :error

echo Copying client jars (flat -- client.jar's own manifest Class-Path expects them alongside it)...
copy /y dist\client.jar dist\submission\client\ >nul
if errorlevel 1 goto :error
copy /y dist\gui.jar dist\submission\client\ >nul
if errorlevel 1 goto :error
copy /y dist\engine.jar dist\submission\client\ >nul
if errorlevel 1 goto :error
copy /y dist\gson-2.11.0.jar dist\submission\client\ >nul
if errorlevel 1 goto :error

echo Copying javafx-sdk\ (this is what lets the client run on a machine with no JavaFX pre-installed)...
xcopy /s /y /i /q javafx-sdk dist\submission\client\javafx-sdk >nul
if errorlevel 1 goto :error

echo Writing dist\submission\client\run-client.bat (flat layout -- no dist\ subfolder inside the submission)...
> dist\submission\client\run-client.bat (
    echo @echo off
    echo java --module-path "%%~dp0javafx-sdk\lib" --add-modules javafx.controls,javafx.fxml -Djava.library.path="%%~dp0javafx-sdk\bin" -jar "%%~dp0client.jar" %%*
)
if errorlevel 1 goto :error

echo.
echo Submission structure assembled under dist\submission\:
echo   dist\submission\GuessMarket.war           -- deploy to Tomcat's webapps\
echo   dist\submission\client\                   -- copy anywhere, then run client\run-client.bat
goto :eof

:error
echo.
echo build-submission.bat FAILED.
exit /b 1
