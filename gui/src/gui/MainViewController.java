package gui;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
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
import dto.TradingMethod;
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

    // Package-private (not private): OrderBookPanelBuilder, a separate class in this same package, needs it.
    IEngine engine;

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
    // Package-private: also called from OrderBookPanelBuilder after a successful order submission.
    void refreshEventsList() {
        try {
            List<EventSummaryDto> events = engine.listEvents();
            eventsListView.getItems().setAll(events);
        } catch (GuessMarketException e) {
            // Not expected to be reachable right after a successful load, but handled defensively rather than assumed away.
            showErrorAlert("Could not list events", e);
        }
    }

    // Re-reads the full user list from the engine and refreshes the Users tab; called right after a successful load.
    // Package-private: also called from OrderBookPanelBuilder after a successful order submission.
    void refreshUsersList() {
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
            renderUserDetails(detail, null);
        } catch (GuessMarketException e) {
            showErrorAlert("Could not load user details", e);
        }
    }

    // Re-fetches username's full detail view after a purchase made from their own tab, then rebuilds all three sections
    // fresh (the balance badge and that event's participation entry both changed, not just the sub-panel being viewed),
    // re-selecting eventIdToReselect afterward so the user doesn't lose their place.
    private void refreshUserDetailsAfterPurchase(String username, int eventIdToReselect) {
        try {
            UserDetailDto detail = engine.getUser(username);
            renderUserDetails(detail, eventIdToReselect);
        } catch (GuessMarketException e) {
            showErrorAlert("Could not load user details", e);
        }
    }

    // Rebuilds the Users tab's details panel from scratch: the account-balance badge, the events-participation list, and a
    // per-event sub-panel (details + participate form) driven by whichever participation gets selected. If
    // eventIdToReselect is non-null, that participation is re-selected programmatically after rebuilding the list.
    private void renderUserDetails(UserDetailDto detail, Integer eventIdToReselect) {
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
                showUserEventDetails(newSelection.eventId(), singleEventDetailsBox, detail.username());
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

        if (eventIdToReselect != null) {
            for (UserEventParticipationDto participation : participationListView.getItems()) {
                if (participation.eventId() == eventIdToReselect) {
                    participationListView.getSelectionModel().select(participation);
                    break;
                }
            }
        }
    }

    // Looks up one event's full status and renders it (details + participate form, pre-bound to username) in the given
    // container; called whenever the Users tab's events-participation list selection changes.
    private void showUserEventDetails(int eventId, VBox container, String username) {
        try {
            EventStatusDto status = engine.getEventStatus(eventId);
            container.getChildren().clear();
            appendEventStatusDisplay(container, status);
            container.getChildren().add(new Separator());
            // Same status gating as the Events tab: never show a control that can only fail.
            container.getChildren().add(buildActionControl(status, username,
                    newStatus -> refreshUserDetailsAfterPurchase(username, eventId)));
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
        // The action control is driven by status: only ever show the one thing that can actually succeed right now.
        eventDetailsBox.getChildren().add(buildActionControl(status, null, this::renderEventDetails));
    }

    // Picks the one action control that makes sense for an event's current status: a NOT_STARTED event can only be
    // opened (by its MM), an ACTIVE one can only be traded on, and a CLOSED one accepts neither. fixedUsername is the
    // Users tab's already-selected user, or null on the Events tab where a picker is needed instead.
    private VBox buildActionControl(EventStatusDto status, String fixedUsername, Consumer<EventStatusDto> onSuccess) {
        return switch (status.status()) {
            case NOT_STARTED -> fixedUsername == null
                    ? buildOpenEventForm(status.eventId(), onSuccess)
                    // The Users tab deliberately has no Open control (scoped to the Events tab), so it just explains why.
                    : new VBox(new Label("This event has not been opened yet — its market maker can open it from the Events tab."));
            case ACTIVE -> buildActiveControls(status, fixedUsername, onSuccess);
            case CLOSED -> new VBox(new Label("This event is closed and no longer accepts trades."));
        };
    }

    // An ACTIVE event shows one of two entirely different control sets depending on trading method. Order Book gets
    // its own real panel (book display + participants + order form) instead of the LMSR participate form, and never
    // a Close form -- the engine refuses to close an Order Book event outright (settlement isn't implemented yet),
    // so per the same "never show a control that can only fail" principle already applied above, Close stays hidden
    // rather than left for the user to hit a guaranteed rejection. TradingMethod has exactly two values, so once
    // ORDER_BOOK is peeled off here, everything below is unconditionally the LMSR case.
    private VBox buildActiveControls(EventStatusDto status, String fixedUsername, Consumer<EventStatusDto> onSuccess) {
        if (status.tradingMethod() == TradingMethod.ORDER_BOOK) {
            return OrderBookPanelBuilder.build(this, status, fixedUsername, onSuccess);
        }
        VBox participateForm = buildParticipateForm(status.eventId(), status.optionOneName(), status.optionTwoName(),
                fixedUsername, onSuccess);
        return new VBox(10, participateForm, new Separator(),
                buildCloseEventForm(status.eventId(), status.optionOneName(), status.optionTwoName(), onSuccess));
    }

    // Builds the "close this event" control: a user picker (market maker only, but let the engine reject a wrong
    // choice rather than pre-filtering the list -- same approach as buildOpenEventForm) plus a winning-option
    // selector and the button.
    private VBox buildCloseEventForm(int eventId, String optionOneName, String optionTwoName, Consumer<EventStatusDto> onSuccess) {
        ComboBox<String> usernameComboBox = buildUsernameComboBox();
        ComboBox<String> winningOptionComboBox = new ComboBox<>();
        winningOptionComboBox.getItems().addAll(optionOneName, optionTwoName);
        winningOptionComboBox.getSelectionModel().selectFirst();
        Button closeButton = new Button("Close Event");
        closeButton.setOnAction(event -> handleCloseEventClick(eventId, usernameComboBox, winningOptionComboBox,
                optionOneName, onSuccess));

        return new VBox(6, new Label("Close this event (market maker only):"),
                new HBox(8, usernameComboBox, winningOptionComboBox, closeButton));
    }

    // Closes the event via the existing IEngine.closeEvent, then redraws through the caller's own callback and
    // refreshes both lists -- closing pays winners and (if on-close) collects commission, so balances change.
    private void handleCloseEventClick(int eventId, ComboBox<String> usernameComboBox, ComboBox<String> winningOptionComboBox,
                                        String optionOneName, Consumer<EventStatusDto> onSuccess) {
        String username = usernameComboBox.getSelectionModel().getSelectedItem();
        String winningOptionName = winningOptionComboBox.getSelectionModel().getSelectedItem();
        if (username == null || username.isBlank() || winningOptionName == null) {
            showErrorAlert("Invalid input", "Select both the market maker and the winning option.");
            return;
        }
        int winningOptionNumber = winningOptionName.equals(optionOneName) ? 1 : 2;
        try {
            EventStatusDto closed = engine.closeEvent(eventId, username, winningOptionNumber);
            onSuccess.accept(closed);
            refreshEventsList();
            refreshUsersList();
        } catch (GuessMarketException e) {
            showErrorAlert("Could not close the event", e);
        }
    }

    // Builds the "open this event" control: a user picker plus the button. Only the event's assigned market maker can
    // succeed — the engine enforces that, so this deliberately doesn't try to pre-filter the list to likely MMs.
    private VBox buildOpenEventForm(int eventId, Consumer<EventStatusDto> onSuccess) {
        ComboBox<String> usernameComboBox = buildUsernameComboBox();
        Button openButton = new Button("Open Event");
        openButton.setOnAction(event -> handleOpenEventClick(eventId, usernameComboBox, onSuccess));

        return new VBox(6, new Label("Open this event (market maker only):"),
                new HBox(8, usernameComboBox, openButton));
    }

    // Opens the event via the existing IEngine.openEvent, then redraws through the caller's own callback and refreshes
    // both lists — opening moves money from the MM into the event account, so balances change.
    private void handleOpenEventClick(int eventId, ComboBox<String> usernameComboBox, Consumer<EventStatusDto> onSuccess) {
        String username = usernameComboBox.getSelectionModel().getSelectedItem();
        if (username == null || username.isBlank()) {
            showErrorAlert("Invalid input", "Select the user opening this event.");
            return;
        }
        try {
            EventStatusDto opened = engine.openEvent(eventId, username);
            onSuccess.accept(opened);
            refreshEventsList();
            refreshUsersList();
        } catch (GuessMarketException e) {
            showErrorAlert("Could not open the event", e);
        }
    }

    // Appends the read-only event status display (prices/shares, account state, winner-if-closed, trade history) to the
    // given container. Shared by the Events tab's full detail panel (which also appends a participate form) and the
    // Users tab's read-only per-event sub-panel (which deliberately doesn't).
    // Only the LMSR-specific "price" concept is hidden for an Order Book event (optionOnePrice/optionTwoPrice are
    // 0.0 there -- see EngineImpl.toStatusDto's own comment on why; the real per-option price picture lives in
    // OrderBookPanelBuilder's book display instead). Everything else here stays real and meaningful for BOTH
    // methods and is shown either way: shares outstanding (the total ever issued for that option), MM account
    // balance, and total commission collected -- the event account still holds the MM's initial funding plus every
    // fill's accumulated commission for an Order Book event too, and nothing else currently displays either number.
    private static void appendEventStatusDisplay(VBox container, EventStatusDto status) {
        boolean isLmsr = status.tradingMethod() == TradingMethod.LMSR;
        container.getChildren().addAll(
                new Label(status.eventName() + "  (id " + status.eventId() + ")  —  " + status.status()),
                new Label("Market Maker: " + status.marketMakerUsername()),
                new Label(formatOptionLine(status.optionOneName(), status.optionOnePrice(), status.optionOneShares(), isLmsr)),
                new Label(formatOptionLine(status.optionTwoName(), status.optionTwoPrice(), status.optionTwoShares(), isLmsr)),
                new Label("Market maker balance: " + formatMoney(status.marketMakerBalance())),
                new Label("Total commission collected: " + formatMoney(status.totalCommissionCollected()))
        );
        if (status.winningOptionName() != null) {
            container.getChildren().add(new Label("Winner: " + status.winningOptionName()));
        }
        container.getChildren().add(new Separator());
        container.getChildren().add(buildTradeHistorySection(status.tradeHistory()));
    }

    // One option's summary line: "price X, shares Y" for LMSR (the curve-price concept is real there), "shares Y"
    // alone for Order Book (price is meaningless there, always 0.0) -- shares outstanding stays meaningful either way.
    private static String formatOptionLine(String optionName, double price, double shares, boolean isLmsr) {
        return optionName + ": " + (isLmsr ? "price " + formatMoney(price) + ", " : "") + "shares " + formatMoney(shares);
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

    // Builds the LMSR participate form: a username source (a fixed Label if fixedUsername is given -- the Users tab,
    // already viewing that user's own area -- otherwise a ComboBox populated from listUsers(), for the Events tab's
    // standalone use), an option selector (by name, not free text), a share-quantity text field, and a Buy button.
    // onSuccess lets each call site redraw itself its own way after a successful purchase.
    private VBox buildParticipateForm(int eventId, String optionOneName, String optionTwoName,
                                       String fixedUsername, Consumer<EventStatusDto> onSuccess) {
        Node usernameNode;
        Supplier<String> usernameSupplier;
        if (fixedUsername != null) {
            usernameNode = new Label("Buying as: " + fixedUsername);
            usernameSupplier = () -> fixedUsername;
        } else {
            ComboBox<String> usernameComboBox = buildUsernameComboBox();
            usernameNode = usernameComboBox;
            usernameSupplier = () -> usernameComboBox.getSelectionModel().getSelectedItem();
        }

        ComboBox<String> optionComboBox = new ComboBox<>();
        optionComboBox.getItems().addAll(optionOneName, optionTwoName);
        optionComboBox.getSelectionModel().selectFirst();

        // Plain TextField, deliberately not a Spinner: Spinner reverts its editor to the last valid value on focus-lost
        // (which fires before the Buy click handler runs), silently discarding whatever invalid text the user typed.
        TextField quantityField = new TextField();
        quantityField.setPromptText("Quantity");
        quantityField.setPrefColumnCount(6);

        Button buyButton = new Button("Buy");
        buyButton.setOnAction(event -> handleBuyClick(eventId, usernameSupplier, optionComboBox, quantityField, onSuccess));

        return new VBox(6, new Label("Participate:"),
                new HBox(8, usernameNode, optionComboBox, quantityField, buyButton));
    }

    // Builds a username picker populated from the currently loaded users, for the Events tab's standalone participate
    // form (which has no already-selected user to bind to, unlike the Users tab's).
    // Package-private: also called from OrderBookPanelBuilder, for the same reason.
    ComboBox<String> buildUsernameComboBox() {
        ComboBox<String> comboBox = new ComboBox<>();
        try {
            for (UserSummaryDto user : engine.listUsers()) {
                comboBox.getItems().add(user.username());
            }
            comboBox.getSelectionModel().selectFirst();
        } catch (GuessMarketException e) {
            // A file is definitely loaded here (we're already showing event details), so this shouldn't happen in
            // practice -- leave the combo box empty rather than block the rest of the form from rendering.
        }
        return comboBox;
    }

    // Reads the quantity field's text exactly as typed; a genuine parse failure is the only ui-level check — everything
    // else (negative/zero/too-large, an unselected/blocked/unknown user) goes straight to the engine to reject.
    private void handleBuyClick(int eventId, Supplier<String> usernameSupplier, ComboBox<String> optionComboBox,
                                 TextField quantityField, Consumer<EventStatusDto> onSuccess) {
        String username = usernameSupplier.get();
        if (username == null || username.isBlank()) {
            showErrorAlert("Invalid input", "Select a user to buy as.");
            return;
        }
        int optionNumber = optionComboBox.getSelectionModel().getSelectedIndex() + 1;
        int shareQuantity;
        try {
            shareQuantity = Integer.parseInt(quantityField.getText().trim());
        } catch (NumberFormatException e) {
            showErrorAlert("Invalid input", "Share quantity must be a whole number.");
            return;
        }
        submitPurchase(eventId, username, optionNumber, shareQuantity, onSuccess);
    }

    // Buys shares via the existing IEngine.participateInEvent, then lets the caller redraw itself (onSuccess) and
    // refreshes both lists — a purchase always changes some user's balance, regardless of which tab triggered it.
    private void submitPurchase(int eventId, String username, int optionNumber, int shareQuantity, Consumer<EventStatusDto> onSuccess) {
        try {
            TradeConfirmationDto confirmation = engine.participateInEvent(eventId, username, optionNumber, shareQuantity);
            showTradeConfirmation(confirmation);
            onSuccess.accept(confirmation.eventStatus());
            refreshEventsList();
            refreshUsersList();
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
    // Package-private: also called from OrderBookPanelBuilder, for the same values (quantities and prices alike).
    static String formatMoney(double value) {
        return String.format(Locale.US, "%.2f", value);
    }

    // Shows a plain Alert for any background-task or engine-call failure — functional only, wording/styling is a later step.
    // Package-private: also called from OrderBookPanelBuilder, so business-rule failures from submitOrder display the same way.
    void showErrorAlert(String headerText, Throwable failure) {
        String message = failure instanceof GuessMarketException
                ? failure.getMessage()
                : String.valueOf(failure);
        showErrorAlert(headerText, message);
    }

    // Shows a plain Alert for a ui-level error that never reached the engine (e.g. unparsable input) — same display, no exception involved.
    // Package-private: also called from OrderBookPanelBuilder, for its own ui-level input checks.
    void showErrorAlert(String headerText, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Error");
        alert.setHeaderText(headerText);
        alert.setContentText(message);
        alert.showAndWait();
    }
}
