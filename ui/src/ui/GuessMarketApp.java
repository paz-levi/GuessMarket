package ui;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

// The JavaFX entry point that will eventually replace ui.Main; kept alongside it until the new UI actually covers its functionality.
public class GuessMarketApp extends Application {

    private static final int INITIAL_WIDTH = 960;
    private static final int INITIAL_HEIGHT = 640;

    // Standard JavaFX launch entry point; hands off to start(Stage) via the JavaFX runtime.
    public static void main(String[] args) {
        launch(args);
    }

    // Loads the root layout from FXML and shows it; no screen content or IEngine wiring yet.
    @Override
    public void start(Stage primaryStage) throws Exception {
        FXMLLoader loader = new FXMLLoader();
        loader.setLocation(GuessMarketApp.class.getResource("MainView.fxml"));
        Parent root = loader.load();

        Scene scene = new Scene(root, INITIAL_WIDTH, INITIAL_HEIGHT);
        scene.getStylesheets().add(GuessMarketApp.class.getResource("styles.css").toExternalForm());

        primaryStage.setTitle("Guess Market");
        primaryStage.setScene(scene);
        // Explicitly not disabled — CLAUDE.md's resize rule forbids using resizable=false as a workaround.
        primaryStage.setResizable(true);
        primaryStage.show();
    }
}
