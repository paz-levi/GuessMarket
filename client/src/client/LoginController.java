package client;

import java.util.function.Consumer;

import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;

import client.http.HttpEngineClient;
import exception.UserAlreadyExistsException;
import gui.common.Async;
import gui.common.Dialogs;

// The Exercise 3 client's login screen: username only, no password (per the spec's own "name only, no signup"
// wording) -- POST /login both registers and establishes the session in one call
// (HttpEngineClient.registerUser -> server.servlets.LoginServlet). A duplicate name (409, UserAlreadyExistsException)
// is shown INLINE and the form is re-enabled to retry with a different name, matching the spec's "error -> retry"
// wording exactly -- not a modal dialog, since this is an expected, everyday case, not a genuine failure. Any other
// failure (server unreachable, 500, ...) falls back to the app's normal Dialogs.showError.
public class LoginController {

    @FXML
    private TextField usernameField;

    @FXML
    private Button loginButton;

    @FXML
    private Label errorLabel;

    @FXML
    private ProgressIndicator loginProgressIndicator;

    private HttpEngineClient httpEngineClient;
    private Consumer<String> onLoginSuccess;

    // Injected once by ClientApp right after loading this screen's FXML.
    public void setHttpEngineClient(HttpEngineClient httpEngineClient) {
        this.httpEngineClient = httpEngineClient;
    }

    // Called with the successfully-registered username once POST /login succeeds; ClientApp uses this to swap the
    // Stage's Scene to the main shell and hand that username down into it.
    public void setOnLoginSuccess(Consumer<String> onLoginSuccess) {
        this.onLoginSuccess = onLoginSuccess;
    }

    // Wires the form; called automatically by FXMLLoader once all @FXML fields are injected.
    @FXML
    private void initialize() {
        hideInlineError();
        loginButton.setOnAction(event -> handleLoginClick());
        usernameField.setOnAction(event -> handleLoginClick()); // Enter in the field submits, same as clicking the button
    }

    // Reads the username field, does the one real HTTP call (POST /login, via HttpEngineClient.registerUser) on a
    // background Task -- see gui.common.Async -- and either reveals the main shell or shows the retry-able error.
    private void handleLoginClick() {
        String username = usernameField.getText() == null ? "" : usernameField.getText().trim();
        if (username.isEmpty()) {
            showInlineError("Enter a username.");
            return;
        }
        hideInlineError();
        setBusy(true);
        Async.run(
                () -> {
                    httpEngineClient.registerUser(username);
                    return username;
                },
                loggedInUsername -> {
                    setBusy(false);
                    onLoginSuccess.accept(loggedInUsername);
                },
                failure -> {
                    setBusy(false);
                    if (failure instanceof UserAlreadyExistsException) {
                        showInlineError("That name is already taken on this server -- pick a different one and try again.");
                    } else {
                        Dialogs.showError("Could not log in", failure);
                    }
                });
    }

    private void setBusy(boolean busy) {
        loginProgressIndicator.setVisible(busy);
        loginProgressIndicator.setManaged(busy);
        loginButton.setDisable(busy);
        usernameField.setDisable(busy);
    }

    private void showInlineError(String message) {
        errorLabel.setText(message);
        errorLabel.setVisible(true);
        errorLabel.setManaged(true);
    }

    private void hideInlineError() {
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
    }
}
