package gui;

import java.io.File;

import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import engine.IEngine;
import exception.UserAlreadyExistsException;
import gui.common.Dialogs;
import gui.tabs.EventsTabController;
import gui.tabs.TabCoordinator;
import gui.tabs.UsersTabController;

// The application shell: the header bar (file loading + color scheme) over the two tabs, which are separate
// <fx:include> sub-components with their own controllers. This class owns only the shell's own concerns and the
// wiring between the tabs -- all list/detail/form logic lives in gui.tabs, and everything reusable lives in
// gui.common / gui.components.
//
// Implements TabCoordinator so either tab can say "data I don't own just changed" without ever referencing the
// other tab. That keeps the wiring a tree (shell -> tabs) rather than a cycle, and means a different shell (e.g.
// Exercise 3's client app) can host the same tabs by implementing the same interface.
public class MainViewController implements TabCoordinator {

    private static final int ARTIFICIAL_DELAY_MS = 1500;

    // Ex1/Ex2's plain IEngine.createDefault() launch (GuessMarketApp, run.bat) has no login screen at all -- Stage 1
    // already made IEngine.loadEventsFile(path, uploaderUsername) require a real, registered uploader identity, so
    // this stands in for "who's uploading" when nobody ever called setUsername(String) (i.e. we're not running under
    // Exercise 3's client, which always sets a real logged-in username right after login). Registered lazily, once,
    // the first time a load actually happens without a real session -- never used when username is non-null.
    private static final String IN_PROCESS_PLACEHOLDER_USERNAME = "gui-local-user";

    @FXML
    private Button loadFileButton;

    @FXML
    private ComboBox<String> colorSchemeComboBox;

    @FXML
    private Label filePathLabel;

    @FXML
    private ProgressIndicator loadProgressIndicator;

    // Injected by FXMLLoader from <fx:include fx:id="eventsTab">, per the fx:id -> XxxController naming convention:
    // the included file's own controller instance is injected into a field named <fx:id> + "Controller".
    @FXML
    private EventsTabController eventsTabController;

    @FXML
    private UsersTabController usersTabController;

    private IEngine engine;

    // Null under the plain in-process launch (GuessMarketApp/run.bat, no login screen). Set once by Exercise 3's
    // ClientApp right after a successful login, before this shell is even shown -- see setUsername below.
    private String username;

    // Injected once by GuessMarketApp/ClientApp right after loading the FXML; the same engine instance is reused for
    // every load, never re-created. Both sub-controllers are already constructed and initialized by this point
    // (nested <fx:include> controllers are built before the including controller's own initialize() runs), so this
    // is the right place to hand the engine down to them.
    public void setEngine(IEngine engine) {
        this.engine = engine;
        eventsTabController.setEngine(engine);
        usersTabController.setEngine(engine);
    }

    // Set once by Exercise 3's ClientApp right after login, with the session's own username -- threaded down to
    // both tabs so every action control acts as the logged-in user rather than needing a picker. Never called by
    // the plain in-process launch, which has no session/login concept at all; both tabs treat a null username
    // exactly as they already did before this method existed (see EventsTabController/UsersTabController).
    public void setUsername(String username) {
        this.username = username;
        eventsTabController.setUsername(username);
        usersTabController.setUsername(username);
    }

    // Wires the header's controls and hands each tab its coordinator; called automatically by FXMLLoader once all
    // @FXML fields (including both included controllers) are injected.
    @FXML
    private void initialize() {
        eventsTabController.setCoordinator(this);
        usersTabController.setCoordinator(this);

        loadFileButton.setOnAction(event -> handleLoadFile());

        // Skins bonus: three schemes, defaulting to "Default" -- selected BY VALUE, not selectFirst()/by index,
        // so correctness never depends on item order (the filter ComboBoxes elsewhere use selectFirst(), which
        // would be unsafe here specifically, since the bonus must launch "off," never on one of the two new
        // schemes, regardless of alphabetical or any other incidental ordering).
        colorSchemeComboBox.getItems().addAll("Default", "Dark", "High Contrast");
        colorSchemeComboBox.getSelectionModel().select("Default");
        colorSchemeComboBox.getSelectionModel().selectedItemProperty().addListener((observable, oldScheme, newScheme) -> applyColorScheme(newScheme));
    }

    // TabCoordinator: routes a refresh request from either tab (or from any shared component) to whichever tab
    // actually owns that data.
    @Override
    public void refreshEvents() {
        eventsTabController.refreshEventsList();
    }

    @Override
    public void refreshUsers() {
        usersTabController.refreshUsersList();
    }

    // Swaps the Scene's active stylesheet to the chosen scheme -- Scene.getStylesheets() is observable, so
    // Scene/Parent re-run CSS resolution across the whole existing scene graph on the next pulse, not just newly
    // created nodes; this is standard JavaFX runtime theme switching, not the app's own custom mechanism.
    // setAll(...) replaces the list's entire contents in one change, so exactly one scheme is ever active -- never
    // a multi-file cascade where the "active" theme would depend on list order. The Scene is reached lazily off
    // the ComboBox itself (colorSchemeComboBox.getScene()), the same pattern handleLoadFile already uses for its
    // owner Window, rather than threading a Scene reference in from GuessMarketApp.
    private void applyColorScheme(String scheme) {
        String resource = switch (scheme) {
            case "Dark" -> "styles-dark.css";
            case "High Contrast" -> "styles-high-contrast.css";
            default -> "styles.css";
        };
        colorSchemeComboBox.getScene().getStylesheets().setAll(
                MainViewController.class.getResource(resource).toExternalForm());
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

    // Runs IEngine.loadEventsFile off the FX thread, showing the progress indicator for the duration (plus a short
    // artificial delay). uploaderUsername is the real logged-in user under Exercise 3's client, or a lazily
    // self-registered placeholder under the plain in-process launch, which has no login screen to get a real one from.
    private void runLoad(File file) {
        String path = file.getAbsolutePath();
        String uploaderUsername = username != null ? username : IN_PROCESS_PLACEHOLDER_USERNAME;
        Task<Void> loadTask = new Task<>() {
            @Override
            protected Void call() throws Exception {
                if (username == null) {
                    try {
                        engine.registerUser(uploaderUsername);
                    } catch (UserAlreadyExistsException e) {
                        // Already registered by an earlier load in this same in-process run -- fine, reuse it.
                    }
                }
                Thread.sleep(ARTIFICIAL_DELAY_MS);
                engine.loadEventsFile(path, uploaderUsername);
                return null;
            }
        };

        loadProgressIndicator.visibleProperty().bind(loadTask.runningProperty());
        loadProgressIndicator.managedProperty().bind(loadTask.runningProperty());
        loadFileButton.disableProperty().bind(loadTask.runningProperty());

        loadTask.setOnSucceeded(event -> {
            filePathLabel.setText(path);
            revealLoadedContent();
            refreshEvents();
            refreshUsers();
        });
        loadTask.setOnFailed(event -> Dialogs.showError("Could not load the events file", loadTask.getException()));

        Thread thread = new Thread(loadTask, "load-events-file");
        thread.setDaemon(true);
        thread.start();
    }

    // Fans the reveal out to both tabs, each of which swaps its own "No file loaded" placeholder for its real
    // content. Idempotent -- safe to call on every successful load, not just the first.
    private void revealLoadedContent() {
        eventsTabController.revealLoadedContent();
        usersTabController.revealLoadedContent();
    }
}
