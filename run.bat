@echo off
java --module-path "%~dp0javafx-sdk\lib" --add-modules javafx.controls,javafx.fxml -Djava.library.path="%~dp0javafx-sdk\bin" -jar "%~dp0dist\gui.jar" %*
