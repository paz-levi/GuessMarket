@echo off
setlocal
cd /d "%~dp0"

rem Self-contained: builds and packages GuessMarket.war (the "server" module) without touching ui/gui, which
rem still don't compile since Ex3 Stage 1 (expected -- fixed in Stage 3-4). Deploys to CATALINA_HOME\webapps if
rem that environment variable is set; otherwise falls back to the Tomcat install this project was developed
rem against, so the script still works on this machine even without CATALINA_HOME exported.
if "%CATALINA_HOME%"=="" (
    set "CATALINA_HOME=C:\apache-tomcat-11.0.25-windows-x64\apache-tomcat-11.0.25"
)
set "TOMCAT_LIB=%CATALINA_HOME%\lib"
set "GSON_JAR=lib\gson-2.11.0.jar"

echo Cleaning previous server build output...
if exist out\engine rmdir /s /q out\engine
if exist out\server rmdir /s /q out\server
if exist out\war rmdir /s /q out\war
mkdir out\engine
mkdir out\server
mkdir out\war\WEB-INF\classes
mkdir out\war\WEB-INF\lib

echo Compiling engine module...
dir /s /b engine\src\*.java > engine-sources.txt
javac --release 25 -d out\engine @engine-sources.txt
if errorlevel 1 goto :error
del engine-sources.txt

echo Packaging engine.jar...
if not exist dist mkdir dist
jar cf dist\engine.jar -C out\engine .
if errorlevel 1 goto :error

echo Compiling server module...
dir /s /b server\src\*.java > server-sources.txt
rem The Tomcat servlet-api jar is listed FIRST in the classpath -- Git Bash/MSYS mangles a ';'-joined multi-entry
rem classpath when the first entry isn't an absolute Windows path, a real quirk hit and confirmed while building
rem this script; cmd.exe itself has no such issue, but the ordering is kept for consistency with how this was
rem verified.
javac --release 25 -cp "%TOMCAT_LIB%\servlet-api.jar;out\engine;%GSON_JAR%" -d out\server @server-sources.txt
if errorlevel 1 goto :error
del server-sources.txt

echo Assembling WAR staging directory...
xcopy /s /y /q server\web\* out\war\ >nul
if errorlevel 1 goto :error
xcopy /s /y /q out\server\* out\war\WEB-INF\classes\ >nul
if errorlevel 1 goto :error
copy /y dist\engine.jar out\war\WEB-INF\lib\ >nul
if errorlevel 1 goto :error
copy /y "%GSON_JAR%" out\war\WEB-INF\lib\ >nul
if errorlevel 1 goto :error
rem servlet-api.jar is DELIBERATELY not copied here -- Tomcat provides it at runtime, and bundling it in the WAR
rem shadows the container's own copy and breaks deployment.

echo Packaging GuessMarket.war...
if exist dist\GuessMarket.war del dist\GuessMarket.war
jar -c -f dist\GuessMarket.war -C out\war .
if errorlevel 1 goto :error

echo Deploying to %CATALINA_HOME%\webapps...
copy /y dist\GuessMarket.war "%CATALINA_HOME%\webapps\" >nul
if errorlevel 1 goto :error

echo.
echo Server build succeeded. dist\GuessMarket.war deployed to %CATALINA_HOME%\webapps\.
echo Base URL once Tomcat is running: http://localhost:8080/GuessMarket
goto :eof

:error
echo.
echo Server build FAILED.
exit /b 1
