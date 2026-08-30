@echo off
setlocal
cd /d "%~dp0"

echo Cleaning previous build output...
if exist out rmdir /s /q out
if exist dist rmdir /s /q dist
mkdir out\engine
mkdir out\ui
mkdir dist

echo Compiling engine module...
dir /s /b engine\src\*.java > engine-sources.txt
javac --release 25 -d out\engine @engine-sources.txt
if errorlevel 1 goto :error
del engine-sources.txt

echo Compiling ui module...
dir /s /b ui\src\*.java > ui-sources.txt
javac --release 25 --module-path javafx-sdk\lib --add-modules javafx.controls,javafx.fxml -d out\ui -cp out\engine @ui-sources.txt
if errorlevel 1 goto :error
del ui-sources.txt

echo Copying ui resources...
xcopy /s /y ui\resources\* out\ui\ >nul
if errorlevel 1 goto :error

echo Packaging engine.jar...
jar cf dist\engine.jar -C out\engine .
if errorlevel 1 goto :error

echo Packaging ui.jar...
jar cfm dist\ui.jar ui-manifest.txt -C out\ui .
if errorlevel 1 goto :error

echo.
echo Build succeeded. Jars are in dist\ — run via run.bat at the project root.
goto :eof

:error
echo.
echo Build FAILED.
exit /b 1
