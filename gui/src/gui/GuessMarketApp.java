package gui;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import engine.IEngine;

// The JavaFX entry point that will eventually replace ui.Main; kept alongside it until the new UI actually covers its functionality.
public class GuessMarketApp extends Application {

    private static final int INITIAL_WIDTH = 960;
    private static final int INITIAL_HEIGHT = 640;

    // Standard JavaFX launch entry point; hands off to start(Stage) via the JavaFX runtime.
    public static void main(String[] args) {
        launch(args);
    }

    // Loads the root layout from FXML, hands the controller the one IEngine instance for the app's whole lifetime, and shows it.
    @Override
    public void start(Stage primaryStage) throws Exception {
        IEngine engine = IEngine.createDefault();

        FXMLLoader loader = new FXMLLoader();
        loader.setLocation(GuessMarketApp.class.getResource("MainView.fxml"));
        Parent root = loader.load();
        MainViewController controller = loader.getController();
        controller.setEngine(engine);

        Scene scene = new Scene(root, INITIAL_WIDTH, INITIAL_HEIGHT);
        scene.getStylesheets().add(GuessMarketApp.class.getResource("styles.css").toExternalForm());

        primaryStage.setTitle("Guess Market");
        primaryStage.setScene(scene);
        // Explicitly not disabled — CLAUDE.md's resize rule forbids using resizable=false as a workaround.
        primaryStage.setResizable(true);
        // A sane practical floor only, not a spec number -- roughly 2/3 of the initial size, so the window can't be
        // dragged down to near-zero while still allowing real shrinking (which the wrapText labels below rely on).
        primaryStage.setMinWidth(640);
        primaryStage.setMinHeight(420);
        primaryStage.show();
        // Known JavaFX quirk, not specific to this app: some Controls (ComboBox in particular) lazily realize their
        // internal Skin, so the very first layout/CSS pass -- which runs synchronously inside show() -- can measure
        // stale sizes for them, visibly mis-allocating space to HBox neighbors (e.g. the Events tab's filter-bar
        // Labels). Any later pulse (a resize, or any interaction that forces requestLayout()) self-corrects -- which
        // is exactly the "doesn't render right until interacted with" symptom reported twice now, in two different
        // places. Forcing one extra layout pass on the *next* pulse (after this one's skins have finished realizing)
        // makes the very first paint already correct, instead of relying on the user's own next interaction to fix it.
        Platform.runLater(() -> {
            scene.getRoot().applyCss();
            scene.getRoot().layout();
        });
    }
}
