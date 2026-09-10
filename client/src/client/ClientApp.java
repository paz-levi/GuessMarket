package client;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import client.http.HttpEngineClient;
import gui.MainViewController;

// The Exercise 3 client's JavaFX entry point: shows the login screen first (client/LoginView.fxml), then -- once
// POST /login succeeds -- swaps the same Stage's Scene to the exact same MainView.fxml/MainViewController the
// plain in-process app (gui.GuessMarketApp) already uses, just backed by an HttpEngineClient instead of EngineImpl.
// Mirrors GuessMarketApp.start's own FXMLLoader -> setEngine -> Scene shape for the main shell (see that class's
// own comments for the resize-floor/first-paint-quirk reasoning, unchanged here) -- reused rather than
// re-implemented, since gui.tabs/gui.components take IEngine as a plain parameter and don't care which
// implementation they're handed.
public class ClientApp extends Application {

    private static final int INITIAL_WIDTH = 960;
    private static final int INITIAL_HEIGHT = 640;

    // Fixed per the plan -- nothing in the spec or docs-reference/ex3-plan.md asks for a configurable server
    // address, and grading runs client and server on the same machine.
    private static final String BASE_URL = "http://localhost:8080/GuessMarket";

    private HttpEngineClient httpEngineClient;
    private Stage primaryStage;

    // Standard JavaFX launch entry point; hands off to start(Stage) via the JavaFX runtime.
    public static void main(String[] args) {
        launch(args);
    }

    // Builds the one HttpEngineClient this session reuses for its whole lifetime (so the /login session cookie
    // persists across every later call -- see HttpEngineClient's own class doc), then shows the login screen.
    @Override
    public void start(Stage primaryStage) throws Exception {
        this.primaryStage = primaryStage;
        this.httpEngineClient = new HttpEngineClient(BASE_URL);

        primaryStage.setTitle("Guess Market");
        // Explicitly not disabled -- CLAUDE.md's resize rule forbids using resizable=false as a workaround.
        primaryStage.setResizable(true);
        primaryStage.setMinWidth(640);
        primaryStage.setMinHeight(420);

        showLoginScreen();
        primaryStage.show();
        forceExtraLayoutPass();
    }

    // Loads the login screen and wires its success callback to swap into the main shell.
    private void showLoginScreen() throws Exception {
        FXMLLoader loader = new FXMLLoader();
        loader.setLocation(LoginController.class.getResource("LoginView.fxml"));
        Parent root = loader.load();
        LoginController controller = loader.getController();
        controller.setHttpEngineClient(httpEngineClient);
        controller.setOnLoginSuccess(this::showMainShell);

        Scene scene = new Scene(root, INITIAL_WIDTH, INITIAL_HEIGHT);
        applyStylesheet(scene);
        primaryStage.setScene(scene);
    }

    // Loads the same MainView.fxml the plain in-process app uses (gui module), hands the controller this session's
    // HttpEngineClient and the just-logged-in username -- gating the WHOLE shell behind login, not just "loaded
    // content" the way MainViewController's own revealLoadedContent() gates an unloaded file within an
    // already-logged-in session -- and swaps the Stage over to it.
    private void showMainShell(String username) {
        try {
            FXMLLoader loader = new FXMLLoader();
            loader.setLocation(MainViewController.class.getResource("MainView.fxml"));
            Parent root = loader.load();
            MainViewController controller = loader.getController();
            controller.setEngine(httpEngineClient);
            controller.setUsername(username);

            Scene scene = new Scene(root, INITIAL_WIDTH, INITIAL_HEIGHT);
            applyStylesheet(scene);
            primaryStage.setScene(scene);
            forceExtraLayoutPass();
        } catch (Exception e) {
            throw new IllegalStateException("Could not load the main application view", e);
        }
    }

    private void applyStylesheet(Scene scene) {
        scene.getStylesheets().add(MainViewController.class.getResource("styles.css").toExternalForm());
    }

    // Same known JavaFX first-paint quirk GuessMarketApp already works around (see that class for the full
    // explanation: a ComboBox's Skin realizes lazily, so the very first CSS+layout pass can measure stale sizes) --
    // applies equally to this app's own first paint of whichever screen is current, login or main shell.
    private void forceExtraLayoutPass() {
        Platform.runLater(() -> {
            if (primaryStage.getScene() != null) {
                primaryStage.getScene().getRoot().applyCss();
                primaryStage.getScene().getRoot().layout();
            }
        });
    }
}
