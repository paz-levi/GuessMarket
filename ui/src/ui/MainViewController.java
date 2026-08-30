package ui;

import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;

// Controller for MainView.fxml; holds references to the header controls only — no behavior wired yet (next step).
public class MainViewController {

    @FXML
    private Button loadFileButton;

    @FXML
    private Label filePathLabel;
}
