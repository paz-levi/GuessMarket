package ui;

import java.io.File;
import java.util.List;
import java.util.Locale;

import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import dto.CommissionMode;
import dto.EventStatusDto;
import dto.EventSummaryDto;
import dto.TradeConfirmationDto;
import dto.TradeRecordDto;
import dto.UserDetailDto;
import dto.UserEventParticipationDto;
import dto.UserSummaryDto;
import engine.IEngine;
import exception.GuessMarketException;

// Controller for MainView.fxml; owns the Load File flow, the Events list/details/participation, the Users list/details, and the shared header's state.
public class MainViewController {

    // Artificial delay so the ProgressIndicator is visible even though the real load is fast — per CLAUDE.md's FileChooser/Task rule.
    private static final int ARTIFICIAL_DELAY_MS = 1500;

    @FXML
    private Button loadFileButton;

    @FXML
    private Label filePathLabel;

    @FXML
    private ProgressIndicator loadProgressIndicator;

    @FXML
    private ListView<EventSummaryDto> eventsListView;

    @FXML
    private VBox eventDetailsBox;

    @FXML
    private ListView<UserSummaryDto> usersListView;

    @FXML
    private VBox userDetailsBox;

    private IEngine engine;

    // Injected once by GuessMarketApp right after loading the FXML; the same engine instance is reused for every load, never re-created.
    void setEngine(IEngine engine) {
        this.engine = engine;
    }

    // Wires the header's Load File button, the Events list's row rendering, and row selection; called automatically by FXMLLoader once all @FXML fields are injected.
    @FXML
    private void initialize() {
        loadFileButton.setOnAction(event -> handleLoadFile());
        eventsListView.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(EventSummaryDto event, boolean empty) {
                super.updateItem(event, empty);
                setText(empty || event == null ? null : formatEventSummary(event));
            }
        });
        eventsListView.getSelectionModel().selectedItemProperty().addListener((observable, oldSelection, newSelection) -> {
            if (newSelection != null) {
                showEventDetails(newSelection.eventId());
            }
        });
        usersListView.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(UserSummaryDto user, boolean empty) {
                super.updateItem(user, empty);
                setText(empty || user == null ? null : formatUserSummary(user));
            }
        });
        usersListView.getSelectionModel().selectedItemProperty().addListener((observable, oldSelection, newSelection) -> {
            if (newSelection != null) {
                showUserDetails(newSelection.username());
            }
        });
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

        loadTask.setOnSucceeded(event -> {
            filePathLabel.setText(path);
            refreshEventsList();
            refreshUsersList();
        });
        loadTask.setOnFailed(event -> showErrorAlert("Could not load the events file", loadTask.getException()));

        Thread thread = new Thread(loadTask, "load-events-file");
        thread.setDaemon(true);
        thread.start();
    }

    // Re-reads the full event list from the engine and refreshes the Events tab; called right after a successful load.
    private void refreshEventsList() {
        try {
            List<EventSummaryDto> events = engine.listEvents();
            eventsListView.getItems().setAll(events);
        } catch (GuessMarketException e) {
            // Not expected to be reachable right after a successful load, but handled defensively rather than assumed away.
            showErrorAlert("Could not list events", e);
        }
    }

    // Re-reads the full user list from the engine and refreshes the Users tab; called right after a successful load.
    private void refreshUsersList() {
        try {
            List<UserSummaryDto> users = engine.listUsers();
            usersListView.getItems().setAll(users);
        } catch (GuessMarketException e) {
            // Not expected to be reachable right after a successful load, but handled defensively rather than assumed away.
            showErrorAlert("Could not list users", e);
        }
    }

    // Looks up one user's full detail view and renders it in the right-hand details panel; called whenever the Users list selection changes.
    private void showUserDetails(String username) {
        try {
            UserDetailDto detail = engine.getUser(username);
            renderUserDetails(detail);
        } catch (GuessMarketException e) {
            showErrorAlert("Could not load user details", e);
        }
    }

    // Rebuilds the Users tab's details panel from scratch: the account-balance badge, the events-participation list, and a
    // read-only per-event sub-panel driven by whichever participation gets selected. No trade/buy actions from here yet.
    private void renderUserDetails(UserDetailDto detail) {
        Label balanceLabel = new Label("Balance: " + formatMoney(detail.balance()) + (detail.blocked() ? "  (BLOCKED)" : ""));
        balanceLabel.getStyleClass().add("balance-badge");
        HBox balanceBadge = new HBox(balanceLabel);
        balanceBadge.setAlignment(Pos.CENTER_RIGHT);

        VBox singleEventDetailsBox = new VBox(10, new Label("Select an event above to view details"));

        ListView<UserEventParticipationDto> participationListView = new ListView<>();
        participationListView.getItems().setAll(detail.activeParticipations());
        participationListView.setPrefHeight(150);
        participationListView.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(UserEventParticipationDto participation, boolean empty) {
                super.updateItem(participation, empty);
                setText(empty || participation == null ? null : formatParticipation(participation));
            }
        });
        participationListView.getSelectionModel().selectedItemProperty().addListener((observable, oldSelection, newSelection) -> {
            if (newSelection != null) {
                showUserEventDetails(newSelection.eventId(), singleEventDetailsBox);
            }
        });

        userDetailsBox.getChildren().setAll(
                balanceBadge,
                new Label("Events Participation / Owner:"),
                participationListView,
                new Separator(),
                new Label("Single event details and trade:"),
                singleEventDetailsBox
        );
    }

    // Looks up one event's full status and renders it read-only (no participate form) in the given container; called
    // whenever the Users tab's events-participation list selection changes.
    private void showUserEventDetails(int eventId, VBox container) {
        try {
            EventStatusDto status = engine.getEventStatus(eventId);
            container.getChildren().clear();
            appendEventStatusDisplay(container, status);
        } catch (GuessMarketException e) {
            showErrorAlert("Could not load event details", e);
        }
    }

    // Looks up one event's full status and renders it in the right-hand details panel; called whenever the Events list selection changes.
    private void showEventDetails(int eventId) {
        try {
            EventStatusDto status = engine.getEventStatus(eventId);
            renderEventDetails(status);
        } catch (GuessMarketException e) {
            showErrorAlert("Could not load event details", e);
        }
    }

    // Rebuilds the details panel's content from scratch: the read-only status display plus the LMSR participate form.
    private void renderEventDetails(EventStatusDto status) {
        eventDetailsBox.getChildren().clear();
        appendEventStatusDisplay(eventDetailsBox, status);
        eventDetailsBox.getChildren().add(new Separator());
        eventDetailsBox.getChildren().add(buildParticipateForm(status.eventId(), status.optionOneName(), status.optionTwoName()));
    }

    // Appends the read-only event status display (prices/shares, account state, winner-if-closed, trade history) to the
    // given container. Shared by the Events tab's full detail panel (which also appends a participate form) and the
    // Users tab's read-only per-event sub-panel (which deliberately doesn't).
    private static void appendEventStatusDisplay(VBox container, EventStatusDto status) {
        container.getChildren().addAll(
                new Label(status.eventName() + "  (id " + status.eventId() + ")  —  " + status.status()),
                new Label(status.optionOneName() + ": price " + formatMoney(status.optionOnePrice())
                        + ", shares " + formatMoney(status.optionOneShares())),
                new Label(status.optionTwoName() + ": price " + formatMoney(status.optionTwoPrice())
                        + ", shares " + formatMoney(status.optionTwoShares())),
                new Label("Market maker balance: " + formatMoney(status.marketMakerBalance())),
                new Label("Total commission collected: " + formatMoney(status.totalCommissionCollected()))
        );
        if (status.winningOptionName() != null) {
            container.getChildren().add(new Label("Winner: " + status.winningOptionName()));
        }
        container.getChildren().add(new Separator());
        container.getChildren().add(buildTradeHistorySection(status.tradeHistory()));
    }

    // Builds the trade-history block: a header plus one row per trade (already newest-first, per EventStatusDto's own contract), or a placeholder when empty.
    private static VBox buildTradeHistorySection(List<TradeRecordDto> tradeHistory) {
        VBox section = new VBox(4, new Label("Trade history:"));
        if (tradeHistory.isEmpty()) {
            section.getChildren().add(new Label("No trades yet."));
        } else {
            for (TradeRecordDto trade : tradeHistory) {
                section.getChildren().add(new Label(trade.optionName() + ": " + formatMoney(trade.quantity())
                        + " share(s) @ " + formatMoney(trade.pricePerShare())
                        + ", commission " + formatMoney(trade.commissionPaid())
                        + ", total " + formatMoney(trade.totalPaid())
                        + "  (" + trade.timestamp() + ")"));
            }
        }
        return section;
    }

    // Builds the LMSR participate form: an option selector (by name, not free text), a share-quantity text field, and a Buy button.
    private VBox buildParticipateForm(int eventId, String optionOneName, String optionTwoName) {
        ComboBox<String> optionComboBox = new ComboBox<>();
        optionComboBox.getItems().addAll(optionOneName, optionTwoName);
        optionComboBox.getSelectionModel().selectFirst();

        // Plain TextField, deliberately not a Spinner: Spinner reverts its editor to the last valid value on focus-lost
        // (which fires before the Buy click handler runs), silently discarding whatever invalid text the user typed.
        TextField quantityField = new TextField();
        quantityField.setPromptText("Quantity");
        quantityField.setPrefColumnCount(6);

        Button buyButton = new Button("Buy");
        buyButton.setOnAction(event -> handleBuyClick(eventId, optionComboBox, quantityField));

        return new VBox(6, new Label("Participate:"),
                new HBox(8, optionComboBox, quantityField, buyButton));
    }

    // Reads the quantity field's text exactly as typed; a genuine parse failure is the only ui-level check — everything
    // else (negative/zero/too-large) goes straight to the engine for IllegalTradeException to reject.
    private void handleBuyClick(int eventId, ComboBox<String> optionComboBox, TextField quantityField) {
        int optionNumber = optionComboBox.getSelectionModel().getSelectedIndex() + 1;
        int shareQuantity;
        try {
            shareQuantity = Integer.parseInt(quantityField.getText().trim());
        } catch (NumberFormatException e) {
            showErrorAlert("Invalid input", "Share quantity must be a whole number.");
            return;
        }
        submitPurchase(eventId, optionNumber, shareQuantity);
    }

    // Buys shares via the existing (untouched) IEngine.participateInEvent, then refreshes both the details panel and the events list.
    private void submitPurchase(int eventId, int optionNumber, int shareQuantity) {
        try {
            TradeConfirmationDto confirmation = engine.participateInEvent(eventId, optionNumber, shareQuantity);
            showTradeConfirmation(confirmation);
            renderEventDetails(confirmation.eventStatus());
            refreshEventsList();
        } catch (GuessMarketException e) {
            showErrorAlert("Could not complete purchase", e);
        }
    }

    // Shows the purchase breakdown (share cost, commission, total) as a simple confirmation Alert.
    private void showTradeConfirmation(TradeConfirmationDto confirmation) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Purchase Complete");
        alert.setHeaderText("Bought " + formatMoney(confirmation.shareQuantity()) + " share(s) of " + confirmation.optionName());
        alert.setContentText("Share cost: " + formatMoney(confirmation.shareCost())
                + "\nCommission: " + formatMoney(confirmation.commissionPaid())
                + "\nTotal paid: " + formatMoney(confirmation.totalPaid()));
        alert.showAndWait();
    }

    // Formats one event's summary row: name, status, trading method, commission rate/mode — every field EventSummaryDto already carries.
    private static String formatEventSummary(EventSummaryDto event) {
        return event.eventName() + "  —  " + event.status() + "  —  " + event.tradingMethod()
                + "  —  " + event.commissionRate() + "% " + formatCommissionMode(event.commissionMode());
    }

    // Small presentation helper, matching ui.Main's own formatCommissionMode wording.
    private static String formatCommissionMode(CommissionMode mode) {
        return mode == CommissionMode.ON_PURCHASE ? "On Purchase" : "On Close";
    }

    // Formats one user's summary row: username, balance, and a blocked marker when applicable — every field UserSummaryDto already carries.
    private static String formatUserSummary(UserSummaryDto user) {
        return user.username() + "  —  " + formatMoney(user.balance()) + (user.blocked() ? "  (BLOCKED)" : "");
    }

    // Formats one row of a user's events-participation list: event name, status, trading method.
    private static String formatParticipation(UserEventParticipationDto participation) {
        return participation.eventName() + "  —  " + participation.eventStatus() + "  —  " + participation.tradingMethod();
    }

    // Pins Locale.US so money/share values can't silently print a comma on a non-English-default JVM — same discipline ui.Main already applies.
    private static String formatMoney(double value) {
        return String.format(Locale.US, "%.2f", value);
    }

    // Shows a plain Alert for any background-task or engine-call failure — functional only, wording/styling is a later step.
    private void showErrorAlert(String headerText, Throwable failure) {
        String message = failure instanceof GuessMarketException
                ? failure.getMessage()
                : String.valueOf(failure);
        showErrorAlert(headerText, message);
    }

    // Shows a plain Alert for a ui-level error that never reached the engine (e.g. unparsable input) — same display, no exception involved.
    private void showErrorAlert(String headerText, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Error");
        alert.setHeaderText(headerText);
        alert.setContentText(message);
        alert.showAndWait();
    }
}
