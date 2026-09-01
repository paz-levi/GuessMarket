@echo off
REM The frozen Exercise 1 console UI, kept as a working reference. No JavaFX flags needed --
REM ui.Main has no javafx.* imports. The Ex2 JavaFX app is run via run.bat instead.
java -jar "%~dp0dist\ui.jar" %*
