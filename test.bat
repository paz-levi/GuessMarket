@echo off
setlocal
cd /d "%~dp0"

set JUNIT_JAR=lib\junit-platform-console-standalone-1.11.3.jar

if not exist out\engine (
    echo out\engine not found, compiling engine module first...
    mkdir out\engine
    dir /s /b engine\src\*.java > engine-sources.txt
    javac --release 25 -d out\engine @engine-sources.txt
    if errorlevel 1 goto :error
    del engine-sources.txt
)

echo Compiling test sources...
if exist out\test rmdir /s /q out\test
mkdir out\test
dir /s /b engine\test\*.java > test-sources.txt
javac --release 25 -cp "out\engine;%JUNIT_JAR%" -d out\test @test-sources.txt
if errorlevel 1 goto :error
del test-sources.txt

echo Running tests...
java -cp "out\engine;out\test;%JUNIT_JAR%" org.junit.platform.console.ConsoleLauncher execute --scan-classpath=out\test --disable-banner
if errorlevel 1 goto :error

goto :eof

:error
echo.
echo Tests FAILED.
exit /b 1
