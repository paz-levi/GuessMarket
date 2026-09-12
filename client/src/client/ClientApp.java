package client;

import java.util.Timer;
import java.util.TimerTask;

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

    // Within the spec's own 0.5-2s range for periodic polling -- a middle value, deliberately: noticeably below
    // the 2s ceiling (stays responsive for a live cross-client demo) without pushing toward the 0.5s floor
    // (avoids unnecessary server/bandwidth load from the full-information events/users polls). A judgment call to
    // tune after an actual hands-on feel-check, not something derived from measurement.
    private static final long POLL_INTERVAL_MS = 1000;

    private HttpEngineClient httpEngineClient;
    private Stage primaryStage;
    private MainViewController mainViewController;
    private Timer pollTimer;

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
        // The only setOnCloseRequest anywhere in this repo -- needed so the periodic-polling Timer (started once
        // login succeeds, see showMainShell) stops promptly on window close rather than continuing to fire against
        // a server nobody's watching any more. Belt-and-suspenders alongside the Timer's own daemon flag, which
        // alone would still let the JVM exit even if this hook were somehow skipped, just not as promptly.
        primaryStage.setOnCloseRequest(event -> {
            if (pollTimer != null) {
                pollTimer.cancel();
            }
        });

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
            controller.setChatSender(httpEngineClient::sendChatMessage);
            this.mainViewController = controller;

            // Mirrors runLoad's own onSucceeded shape (reveal, then refresh both tabs) minus the filePathLabel
            // update, which has no meaning before any upload happens in this window. Events/users are shared
            // server-side state under Exercise 3 -- a fresh logged-in window has real data to show immediately
            // (at minimum yourself, in the users list; possibly events another client already uploaded), so there
            // is no reason to hide either tab behind "has THIS window personally loaded a file" the way Ex2's
            // single in-process engine required. See MainViewController.revealLoadedContent's own doc.
            controller.revealLoadedContent();
            controller.refreshEvents();
            controller.refreshUsers();

            Scene scene = new Scene(root, INITIAL_WIDTH, INITIAL_HEIGHT);
            applyStylesheet(scene);
            primaryStage.setScene(scene);
            forceExtraLayoutPass();
            startPolling();
        } catch (Exception e) {
            throw new IllegalStateException("Could not load the main application view", e);
        }
    }

    // Starts the real periodic sync -- everything except your own actions' instant refresh-after-success (those
    // call sites are unchanged) depends on this to surface OTHER users' changes. TimerTask/Timer per
    // docs-reference/ex3-plan.md's own lecture-confirmed client-side mechanism; the whole tick body runs inside
    // Platform.runLater since Async.run (which refreshEvents/refreshUsers/pollLedger/pollChat each use internally)
    // has, until now, only ever been invoked from the FX Application Thread -- a raw TimerTask.run() executes on the
    // Timer's own background thread instead, so this avoids ever exercising that untested path. Deliberately
    // reuses the exact same refreshEvents/refreshUsers/pollLedger/pollChat entry points every click-driven action
    // already uses rather than duplicating HTTP-calling logic on the Timer's own thread. pollChat is the chat
    // bonus's own delta poll, folded into this same 1000ms tick rather than a second Timer -- no reason to diverge
    // from what's already running (docs-reference/ex3-plan.md's Chat bonus scope).
    private void startPolling() {
        pollTimer = new Timer("guessmarket-poll", true); // daemon -- see the setOnCloseRequest hook's own comment
        pollTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                Platform.runLater(() -> {
                    mainViewController.refreshEvents();
                    mainViewController.refreshUsers();
                    mainViewController.pollLedger(httpEngineClient::getLedgerDelta);
                    mainViewController.pollChat(httpEngineClient::getChatDelta);
                });
            }
        }, POLL_INTERVAL_MS, POLL_INTERVAL_MS);
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
