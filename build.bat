@echo off
setlocal
cd /d "%~dp0"

echo Cleaning previous build output...
if exist out rmdir /s /q out
if exist dist rmdir /s /q dist
mkdir out\engine
mkdir out\ui
mkdir out\gui
mkdir dist

echo Compiling engine module...
dir /s /b engine\src\*.java > engine-sources.txt
javac --release 25 -d out\engine @engine-sources.txt
if errorlevel 1 goto :error
del engine-sources.txt

echo Compiling ui module (Ex1 console reference -- no JavaFX)...
dir /s /b ui\src\*.java > ui-sources.txt
javac --release 25 -d out\ui -cp out\engine @ui-sources.txt
if errorlevel 1 goto :error
del ui-sources.txt

echo Compiling gui module (JavaFX)...
dir /s /b gui\src\*.java > gui-sources.txt
javac --release 25 --module-path javafx-sdk\lib --add-modules javafx.controls,javafx.fxml -d out\gui -cp out\engine @gui-sources.txt
if errorlevel 1 goto :error
del gui-sources.txt

echo Copying gui resources...
xcopy /s /y gui\resources\* out\gui\ >nul
if errorlevel 1 goto :error

echo Packaging engine.jar...
jar cf dist\engine.jar -C out\engine .
if errorlevel 1 goto :error

echo Packaging ui.jar...
jar cfm dist\ui.jar ui-manifest.txt -C out\ui .
if errorlevel 1 goto :error

echo Packaging gui.jar...
jar cfm dist\gui.jar gui-manifest.txt -C out\gui .
if errorlevel 1 goto :error

echo.
echo Build succeeded. Jars are in dist\ — run the JavaFX app via run.bat, the Ex1 console via run-console.bat.
goto :eof

:error
echo.
echo Build FAILED.
exit /b 1
