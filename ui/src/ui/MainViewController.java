package ui;

import java.io.File;

import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import engine.IEngine;
import exception.GuessMarketException;

// Controller for MainView.fxml; owns the Load File flow (FileChooser -> background Task -> engine) and the shared header's state. Tab content still unwired.
public class MainViewController {

    // Artificial delay so the ProgressIndicator is visible even though the real load is fast — per CLAUDE.md's FileChooser/Task rule.
    private static final int ARTIFICIAL_DELAY_MS = 1500;

    @FXML
    private Button loadFileButton;

    @FXML
    private Label filePathLabel;

    @FXML
    private ProgressIndicator loadProgressIndicator;

    private IEngine engine;

    // Injected once by GuessMarketApp right after loading the FXML; the same engine instance is reused for every load, never re-created.
    void setEngine(IEngine engine) {
        this.engine = engine;
    }

    // Wires the header's Load File button; called automatically by FXMLLoader once all @FXML fields are injected.
    @FXML
    private void initialize() {
        loadFileButton.setOnAction(event -> handleLoadFile());
    }

    // Opens a FileChooser (never a typed path or a fixed directory) and, on a selection, loads it on a background Task.
    private void handleLoadFile() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Select Events XML File");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("XML files", "*.xml"));

        Window owner = loadFileButton.getScene().getWindow();
        File selectedFile = fileChooser.showOpenDialog(owner);
        if (selectedFile == null) {
            return;
        }

        runLoad(selectedFile);
    }

    // Runs IEngine.loadEventsFile off the FX thread, showing the progress indicator for the duration (plus a short artificial delay).
    private void runLoad(File file) {
        String path = file.getAbsolutePath();
        Task<Void> loadTask = new Task<>() {
            @Override
            protected Void call() throws Exception {
                Thread.sleep(ARTIFICIAL_DELAY_MS);
                engine.loadEventsFile(path);
                return null;
            }
        };

        loadProgressIndicator.visibleProperty().bind(loadTask.runningProperty());
        loadProgressIndicator.managedProperty().bind(loadTask.runningProperty());
        loadFileButton.disableProperty().bind(loadTask.runningProperty());

        loadTask.setOnSucceeded(event -> filePathLabel.setText(path));
        loadTask.setOnFailed(event -> showLoadFailure(loadTask.getException()));

        Thread thread = new Thread(loadTask, "load-events-file");
        thread.setDaemon(true);
        thread.start();
    }

    // Shows the load failure as a plain Alert — functional only, wording/styling is a later step.
    private void showLoadFailure(Throwable failure) {
        String message = failure instanceof GuessMarketException
                ? failure.getMessage()
                : String.valueOf(failure);
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Load Failed");
        alert.setHeaderText("Could not load the events file");
        alert.setContentText(message);
        alert.showAndWait();
    }
}
