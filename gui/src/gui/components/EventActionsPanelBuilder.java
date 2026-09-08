package gui.components;

import java.util.function.Consumer;
import java.util.function.Supplier;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import dto.EventStatusDto;
import dto.TradeConfirmationDto;
import dto.TradingMethod;
import engine.IEngine;
import exception.GuessMarketException;
import gui.common.Dialogs;
import gui.common.Labels;
import gui.tabs.TabCoordinator;

// Every control that ACTS on an event -- open, participate/trade, submit order, close -- picked by the event's
// current status. Shared by both tabs (the Events tab's full detail panel and the Users tab's per-event
// sub-panel), which is exactly why it lives here as a component rather than inside either tab's controller.
//
// Takes the engine and a TabCoordinator directly rather than a host controller, so any module's screens can build
// the identical controls without depending on this app's own shell.
public final class EventActionsPanelBuilder {

    private EventActionsPanelBuilder() {
    }

    // Picks the one action control that makes sense for an event's current status: a NOT_STARTED event can only be
    // opened (by its MM), an ACTIVE one can only be traded on, and a CLOSED one accepts neither. fixedUsername is the
    // Users tab's already-selected user, or null on the Events tab where a picker is needed instead.
    public static VBox build(IEngine engine, TabCoordinator coordinator, EventStatusDto status,
                             String fixedUsername, Consumer<EventStatusDto> onSuccess) {
        return switch (status.status()) {
            case NOT_STARTED -> fixedUsername == null
                    ? buildOpenEventForm(engine, coordinator, status.eventId(), onSuccess)
                    // The Users tab deliberately has no Open control (scoped to the Events tab), so it just explains why.
                    : new VBox(Labels.wrapping("This event has not been opened yet — its market maker can open it from the Events tab."));
            case ACTIVE -> buildActiveControls(engine, coordinator, status, fixedUsername, onSuccess);
            case CLOSED -> new VBox(Labels.wrapping("This event is closed and no longer accepts trades."));
        };
    }

    // An ACTIVE event's controls: the trading form for its own method, plus the MM-only close form beneath it.
    private static VBox buildActiveControls(IEngine engine, TabCoordinator coordinator, EventStatusDto status,
                                            String fixedUsername, Consumer<EventStatusDto> onSuccess) {
        if (status.tradingMethod() == TradingMethod.ORDER_BOOK) {
            return new VBox(10,
                    OrderBookPanelBuilder.build(engine, coordinator, status, fixedUsername, onSuccess),
                    new Separator(),
                    buildCloseEventForm(engine, coordinator, status.eventId(), status.optionOneName(), status.optionTwoName(), onSuccess));
        }
        VBox participateForm = buildParticipateForm(engine, coordinator, status.eventId(),
                status.optionOneName(), status.optionTwoName(), fixedUsername, onSuccess);
        return new VBox(10, participateForm, new Separator(),
                buildCloseEventForm(engine, coordinator, status.eventId(), status.optionOneName(), status.optionTwoName(), onSuccess));
    }

    // Builds the "close this event" control: a user picker (market maker only, but let the engine reject a wrong
    // choice rather than pre-filtering the list -- same approach as buildOpenEventForm) plus a winning-option
    // selector and the button.
    private static VBox buildCloseEventForm(IEngine engine, TabCoordinator coordinator, int eventId,
                                            String optionOneName, String optionTwoName, Consumer<EventStatusDto> onSuccess) {
        ComboBox<String> usernameComboBox = UsernamePicker.build(engine);
        ComboBox<String> winningOptionComboBox = new ComboBox<>();
        winningOptionComboBox.getItems().addAll(optionOneName, optionTwoName);
        winningOptionComboBox.getSelectionModel().selectFirst();
        Button closeButton = new Button("Close Event");
        closeButton.setOnAction(event -> handleCloseEventClick(engine, coordinator, eventId, usernameComboBox,
                winningOptionComboBox, optionOneName, onSuccess));

        return new VBox(6, Labels.sectionHeader("Close this event (market maker only):"),
                new HBox(8, usernameComboBox, winningOptionComboBox, closeButton));
    }

    // Closes the event via the existing IEngine.closeEvent, then redraws through the caller's own callback and
    // refreshes both lists -- closing pays winners and (if on-close) collects commission, so balances change.
    private static void handleCloseEventClick(IEngine engine, TabCoordinator coordinator, int eventId,
                                              ComboBox<String> usernameComboBox, ComboBox<String> winningOptionComboBox,
                                              String optionOneName, Consumer<EventStatusDto> onSuccess) {
        String username = usernameComboBox.getSelectionModel().getSelectedItem();
        String winningOptionName = winningOptionComboBox.getSelectionModel().getSelectedItem();
        if (username == null || username.isBlank() || winningOptionName == null) {
            Dialogs.showError("Invalid input", "Select both the market maker and the winning option.");
            return;
        }
        int winningOptionNumber = winningOptionName.equals(optionOneName) ? 1 : 2;
        try {
            EventStatusDto closed = engine.closeEvent(eventId, username, winningOptionNumber);
            onSuccess.accept(closed);
            coordinator.refreshEvents();
            coordinator.refreshUsers();
        } catch (GuessMarketException e) {
            Dialogs.showError("Could not close the event", e);
        }
    }

    // Builds the "open this event" control: a user picker plus the button. Only the event's assigned market maker can
    // succeed — the engine enforces that, so this deliberately doesn't try to pre-filter the list to likely MMs.
    private static VBox buildOpenEventForm(IEngine engine, TabCoordinator coordinator, int eventId,
                                           Consumer<EventStatusDto> onSuccess) {
        ComboBox<String> usernameComboBox = UsernamePicker.build(engine);
        Button openButton = new Button("Open Event");
        openButton.setOnAction(event -> handleOpenEventClick(engine, coordinator, eventId, usernameComboBox, onSuccess));

        return new VBox(6, Labels.sectionHeader("Open this event (market maker only):"),
                new HBox(8, usernameComboBox, openButton));
    }

    // Opens the event via the existing IEngine.openEvent, then redraws through the caller's own callback and refreshes
    // both lists — opening moves money from the MM into the event account, so balances change.
    private static void handleOpenEventClick(IEngine engine, TabCoordinator coordinator, int eventId,
                                             ComboBox<String> usernameComboBox, Consumer<EventStatusDto> onSuccess) {
        String username = usernameComboBox.getSelectionModel().getSelectedItem();
        if (username == null || username.isBlank()) {
            Dialogs.showError("Invalid input", "Select the user opening this event.");
            return;
        }
        try {
            EventStatusDto opened = engine.openEvent(eventId, username);
            onSuccess.accept(opened);
            coordinator.refreshEvents();
            coordinator.refreshUsers();
        } catch (GuessMarketException e) {
            Dialogs.showError("Could not open the event", e);
        }
    }

    // Builds the LMSR participate form: a username source (a fixed Label if fixedUsername is given -- the Users tab,
    // already viewing that user's own area -- otherwise a ComboBox populated from listUsers(), for the Events tab's
    // standalone use), an option selector (by name, not free text), a share-quantity text field, and a Buy button.
    // onSuccess lets each call site redraw itself its own way after a successful purchase.
    private static VBox buildParticipateForm(IEngine engine, TabCoordinator coordinator, int eventId,
                                             String optionOneName, String optionTwoName,
                                             String fixedUsername, Consumer<EventStatusDto> onSuccess) {
        Node usernameNode;
        Supplier<String> usernameSupplier;
        if (fixedUsername != null) {
            usernameNode = new Label("Buying as: " + fixedUsername);
            usernameSupplier = () -> fixedUsername;
        } else {
            ComboBox<String> usernameComboBox = UsernamePicker.build(engine);
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
        buyButton.setOnAction(event -> handleBuyClick(engine, coordinator, eventId, usernameSupplier, optionComboBox,
                quantityField, onSuccess));

        return new VBox(6, Labels.sectionHeader("Participate:"),
                new HBox(8, usernameNode, optionComboBox, quantityField, buyButton));
    }

    // Reads the quantity field's text exactly as typed; a genuine parse failure is the only ui-level check — everything
    // else (negative/zero/too-large, an unselected/blocked/unknown user) goes straight to the engine to reject.
    private static void handleBuyClick(IEngine engine, TabCoordinator coordinator, int eventId,
                                       Supplier<String> usernameSupplier, ComboBox<String> optionComboBox,
                                       TextField quantityField, Consumer<EventStatusDto> onSuccess) {
        String username = usernameSupplier.get();
        if (username == null || username.isBlank()) {
            Dialogs.showError("Invalid input", "Select a user to buy as.");
            return;
        }
        int optionNumber = optionComboBox.getSelectionModel().getSelectedIndex() + 1;
        int shareQuantity;
        try {
            shareQuantity = Integer.parseInt(quantityField.getText().trim());
        } catch (NumberFormatException e) {
            Dialogs.showError("Invalid input", "Share quantity must be a whole number.");
            return;
        }
        submitPurchase(engine, coordinator, eventId, username, optionNumber, shareQuantity, onSuccess);
    }

    // Buys shares via the existing IEngine.participateInEvent, then lets the caller redraw itself (onSuccess) and
    // refreshes both lists — a purchase always changes some user's balance, regardless of which tab triggered it.
    private static void submitPurchase(IEngine engine, TabCoordinator coordinator, int eventId, String username,
                                       int optionNumber, int shareQuantity, Consumer<EventStatusDto> onSuccess) {
        try {
            TradeConfirmationDto confirmation = engine.participateInEvent(eventId, username, optionNumber, shareQuantity);
            Dialogs.showTradeConfirmation(confirmation);
            onSuccess.accept(confirmation.eventStatus());
            coordinator.refreshEvents();
            coordinator.refreshUsers();
        } catch (GuessMarketException e) {
            Dialogs.showError("Could not complete purchase", e);
        }
    }
}
