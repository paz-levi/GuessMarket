package gui.components;

import java.util.List;
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
import dto.OrderBookSnapshotDto;
import dto.OrderDto;
import dto.OrderResultDto;
import dto.OrderSide;
import dto.ParticipantDto;
import dto.SubmitOrderRequestDto;
import engine.IEngine;
import exception.GuessMarketException;
import gui.common.Dialogs;
import gui.common.Formatters;
import gui.common.Labels;
import gui.tabs.TabCoordinator;

// Builds the Order Book event-detail panel (both option books, participants, order submission form) that
// EventActionsPanelBuilder delegates to for ORDER_BOOK events. A plain static-method helper class, not FXML or a
// separate Controller -- this is a self-contained panel with no screen state of its own, so a Controller would add
// a lifecycle it doesn't need.
//
// Takes the engine and a TabCoordinator directly rather than a host controller, so any module's screens can build
// the identical panel without depending on this app's own shell.
public final class OrderBookPanelBuilder {

    private OrderBookPanelBuilder() {
    }

    // Builds the whole panel: both option books side by side, participants below them, and the order submission
    // form below that -- per docs-reference/ui-sketch-layout.md's Events-tab structure.
    public static VBox build(IEngine engine, TabCoordinator coordinator, EventStatusDto status,
                             String fixedUsername, Consumer<EventStatusDto> onSuccess) {
        List<OrderBookSnapshotDto> books = status.orderBooks();
        HBox booksRow = new HBox(10,
                buildOptionBookPanel(books.get(0)),
                buildOptionBookPanel(books.get(1)));

        return new VBox(10,
                booksRow,
                new Separator(),
                buildParticipantsSection(status.participants()),
                new Separator(),
                buildOrderSubmissionForm(engine, coordinator, status, fixedUsername, onSuccess));
    }

    // One option's book: its name, the LAST/BID/ASK/MID/SPREAD stats line, and its resting bids/asks.
    private static VBox buildOptionBookPanel(OrderBookSnapshotDto book) {
        Label statsLine = Labels.wrapping("LAST: " + Formatters.nullableDollars(book.lastPrice())
                + "  BID: " + Formatters.nullableDollars(book.bidPrice())
                + "  ASK: " + Formatters.nullableDollars(book.askPrice())
                + "  MID: " + Formatters.nullableDollars(book.midPrice())
                + "  SPREAD: " + Formatters.nullableDollars(book.spread()));

        return new VBox(6,
                Labels.sectionHeader(book.optionName() + " order book"),
                statsLine,
                buildOrderListSection("Resting bids:", book.restingBids(), "No resting bids."),
                buildOrderListSection("Resting asks:", book.restingAsks(), "No resting asks."));
    }

    // One side of a book (bids or asks) as a plain list of rows -- matching the existing lightweight style
    // the trade-history section already uses for read-only display rows, not a ListView with a custom cell factory.
    private static VBox buildOrderListSection(String header, List<OrderDto> orders, String emptyText) {
        VBox section = new VBox(4, Labels.sectionHeader(header));
        if (orders.isEmpty()) {
            section.getChildren().add(new Label(emptyText));
        } else {
            for (OrderDto order : orders) {
                section.getChildren().add(Labels.wrapping(formatOrderRow(order)));
            }
        }
        return section;
    }

    private static String formatOrderRow(OrderDto order) {
        return order.username() + ": " + order.side() + " " + Formatters.money(order.quantity())
                + " at " + Formatters.dollars(order.price());
    }

    // Full-width participants list: one row per user holding shares of either option, or a placeholder when empty
    // (rare in practice -- the MM already holds both options' initial allocation as soon as the event opens).
    private static VBox buildParticipantsSection(List<ParticipantDto> participants) {
        VBox section = new VBox(4, Labels.sectionHeader("Participants:"));
        if (participants.isEmpty()) {
            section.getChildren().add(new Label("No participants yet."));
        } else {
            for (ParticipantDto participant : participants) {
                section.getChildren().add(Labels.wrapping(formatParticipantRow(participant)));
            }
        }
        return section;
    }

    private static String formatParticipantRow(ParticipantDto participant) {
        return participant.username()
                + ": option 1 " + Formatters.money(participant.optionOneShares())
                + " share(s) (value " + Formatters.dollars(participant.optionOneValue()) + ")"
                + ", option 2 " + Formatters.money(participant.optionTwoShares())
                + " share(s) (value " + Formatters.dollars(participant.optionTwoValue()) + ")";
    }

    // Builds the order submission form: a username source (fixed Label from the Users tab, or a ComboBox on the
    // Events tab -- identical pattern to EventActionsPanelBuilder's participate form), side, option (by name),
    // quantity and price fields, and the Submit button.
    private static VBox buildOrderSubmissionForm(IEngine engine, TabCoordinator coordinator, EventStatusDto status,
                                                 String fixedUsername, Consumer<EventStatusDto> onSuccess) {
        Node usernameNode;
        Supplier<String> usernameSupplier;
        if (fixedUsername != null) {
            usernameNode = new Label("Trading as: " + fixedUsername);
            usernameSupplier = () -> fixedUsername;
        } else {
            ComboBox<String> usernameComboBox = UsernamePicker.build(engine);
            usernameNode = usernameComboBox;
            usernameSupplier = () -> usernameComboBox.getSelectionModel().getSelectedItem();
        }

        ComboBox<OrderSide> sideComboBox = new ComboBox<>();
        sideComboBox.getItems().addAll(OrderSide.BUY, OrderSide.SELL);
        sideComboBox.getSelectionModel().selectFirst();

        ComboBox<String> optionComboBox = new ComboBox<>();
        optionComboBox.getItems().addAll(status.optionOneName(), status.optionTwoName());
        optionComboBox.getSelectionModel().selectFirst();

        // Plain TextFields, deliberately not Spinners -- same reason the participate form's quantity field already
        // avoids Spinner: it reverts to its last valid value on focus-lost, before the Submit click handler runs.
        TextField quantityField = new TextField();
        quantityField.setPromptText("Quantity");
        quantityField.setPrefColumnCount(6);
        TextField priceField = new TextField();
        priceField.setPromptText("Price");
        priceField.setPrefColumnCount(6);

        Button submitButton = new Button("Submit Order");
        submitButton.setOnAction(event -> handleSubmitOrderClick(engine, coordinator, status.eventId(), usernameSupplier,
                sideComboBox, optionComboBox, quantityField, priceField, onSuccess));

        return new VBox(6, Labels.sectionHeader("Submit order:"),
                new HBox(8, usernameNode, sideComboBox, optionComboBox, quantityField, priceField, submitButton));
    }

    // Reads the form's fields exactly as entered; the only ui-level checks are "is something selected" and "does
    // this parse as a number" -- every business rule (price ceiling, non-positive quantity, selling unheld shares,
    // a blocked user) is already enforced server-side by IEngine.submitOrder, so none of it is duplicated here.
    private static void handleSubmitOrderClick(IEngine engine, TabCoordinator coordinator, int eventId,
                                               Supplier<String> usernameSupplier, ComboBox<OrderSide> sideComboBox,
                                               ComboBox<String> optionComboBox, TextField quantityField,
                                               TextField priceField, Consumer<EventStatusDto> onSuccess) {
        String username = usernameSupplier.get();
        if (username == null || username.isBlank()) {
            Dialogs.showError("Invalid input", "Select a user to trade as.");
            return;
        }
        OrderSide side = sideComboBox.getSelectionModel().getSelectedItem();
        if (side == null) {
            Dialogs.showError("Invalid input", "Select a side (buy or sell).");
            return;
        }
        int optionNumber = optionComboBox.getSelectionModel().getSelectedIndex() + 1;
        double quantity;
        double price;
        try {
            quantity = Double.parseDouble(quantityField.getText().trim());
            // Rounded to exactly 2 decimals here, not just displayed that way: an untruncated typed value (e.g.
            // "0.333") could leave the mint stage's exact-d invariant (restingPrice + complementaryPrice == d) a
            // fraction of a cent off. Quantity is deliberately left alone -- Order Book quantities are genuinely
            // fractional at the type level (double), unlike LMSR's int, so no rounding applies there.
            price = roundToCents(Double.parseDouble(priceField.getText().trim()));
        } catch (NumberFormatException e) {
            Dialogs.showError("Invalid input", "Quantity and price must be numbers.");
            return;
        }

        SubmitOrderRequestDto request = new SubmitOrderRequestDto(username, eventId, optionNumber, side, quantity, price);
        submitOrder(engine, coordinator, request, onSuccess);
    }

    // Mirrors OrderBookExecutor.roundToCents's own convention -- that one is engine-private and unreachable from
    // gui, so this is a small, deliberate duplication of the same one-line formula, not a shared call.
    private static double roundToCents(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    // Submits the order via the existing IEngine.submitOrder, then lets the caller redraw itself (onSuccess) from
    // the result's own nested eventStatus() -- no second getEventStatus call -- and refreshes both lists, matching
    // every other trading action: a fill moves money between a buyer and seller, so both the events and the users
    // lists can go stale otherwise.
    private static void submitOrder(IEngine engine, TabCoordinator coordinator, SubmitOrderRequestDto request,
                                    Consumer<EventStatusDto> onSuccess) {
        try {
            OrderResultDto result = engine.submitOrder(request);
            Dialogs.showOrderConfirmation(result);
            onSuccess.accept(result.eventStatus());
            coordinator.refreshEvents();
            coordinator.refreshUsers();
        } catch (GuessMarketException e) {
            Dialogs.showError("Could not submit order", e);
        }
    }
}
