@echo off
setlocal
cd /d "%~dp0web-client"

rem Ex4 (web client bonus) -- a separate, plain npm-based client against the same, unchanged Tomcat server
rem (server\web\... / GuessMarket.war, started the exact same way as for Exercise 3). Only "npm install" is allowed
rem to run automatically per the spec's own rule -- no other tool install is assumed or requested here; "npm run
rem dev" starts the actual Vite dev server the grader opens in a browser.
echo Installing web client dependencies (npm install)...
call npm install
if errorlevel 1 goto :error

echo.
echo Starting the web client dev server...
echo Once it is up, open the URL it prints (default http://localhost:5173) in a browser.
echo The Tomcat server (GuessMarket.war) must already be running on http://localhost:8080 -- see the main
echo project README for how to start it; this client makes real cross-origin requests to that server.
echo.
call npm run dev
goto :eof

:error
echo.
echo web client setup FAILED.
exit /b 1
