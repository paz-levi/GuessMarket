@echo off
setlocal
cd /d "%~dp0"

echo Cleaning previous build output...
if exist out rmdir /s /q out
if exist dist rmdir /s /q dist
mkdir out\engine
mkdir out\ui
mkdir out\gui
mkdir out\client
mkdir dist

echo Compiling engine module...
dir /s /b engine\src\*.java > engine-sources.txt
javac --release 25 -d out\engine @engine-sources.txt
if errorlevel 1 goto :error
del engine-sources.txt

echo Packaging engine.jar...
jar cf dist\engine.jar -C out\engine .
if errorlevel 1 goto :error

echo Compiling ui module (Ex1 console reference -- no JavaFX)...
rem ui has been broken since the Exercise 3 Stage 1 engine migration (int eventId -> String eventName) and is not
rem part of the Ex3 client build -- it's a frozen, dev-only reference, never packaged for submission (see
rem ARCHITECTURE.md / CLAUDE.md). A failure here is expected and must not block engine/gui/client from building.
dir /s /b ui\src\*.java > ui-sources.txt
javac --release 25 -d out\ui -cp out\engine @ui-sources.txt
if errorlevel 1 (
    echo WARNING: ui module failed to compile -- frozen Ex1 reference, not part of the Ex3 client build. Skipping ui.jar.
    set UI_BUILD_FAILED=1
) else (
    jar cfm dist\ui.jar ui-manifest.txt -C out\ui .
    if errorlevel 1 goto :error
)
del ui-sources.txt

echo Compiling gui module (JavaFX)...
dir /s /b gui\src\*.java > gui-sources.txt
javac --release 25 --module-path javafx-sdk\lib --add-modules javafx.controls,javafx.fxml -d out\gui -cp out\engine @gui-sources.txt
if errorlevel 1 goto :error
del gui-sources.txt

echo Copying gui resources...
xcopy /s /y gui\resources\* out\gui\ >nul
if errorlevel 1 goto :error

echo Packaging gui.jar...
jar cfm dist\gui.jar gui-manifest.txt -C out\gui .
if errorlevel 1 goto :error

echo Compiling client module (JavaFX + Gson -- the Exercise 3 HTTP-backed client)...
dir /s /b client\src\*.java > client-sources.txt
javac --release 25 --module-path javafx-sdk\lib --add-modules javafx.controls,javafx.fxml -d out\client -cp "out\engine;out\gui;lib\gson-2.11.0.jar" @client-sources.txt
if errorlevel 1 goto :error
del client-sources.txt

echo Copying client resources...
xcopy /s /y client\resources\* out\client\ >nul
if errorlevel 1 goto :error

echo Packaging client.jar...
copy /y lib\gson-2.11.0.jar dist\ >nul
if errorlevel 1 goto :error
jar cfm dist\client.jar client-manifest.txt -C out\client .
if errorlevel 1 goto :error

echo.
if defined UI_BUILD_FAILED (
    echo Build succeeded EXCEPT ui.jar ^(skipped -- see WARNING above, expected^). Jars are in dist\.
) else (
    echo Build succeeded. Jars are in dist\.
)
echo Run the JavaFX Ex2 app via run.bat, the Exercise 3 HTTP client via run-client.bat, the Ex1 console via run-console.bat.
goto :eof

:error
echo.
echo Build FAILED.
exit /b 1
