package gui;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javafx.scene.Node;
import javafx.scene.control.Alert;
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
import exception.GuessMarketException;

// Builds the Order Book event-detail panel (both option books, participants, order submission form) that
// MainViewController.buildActiveControls delegates to for ORDER_BOOK events. A plain static-method helper class,
// not FXML or a separate Controller, per CLAUDE.md's recorded <fx:include>-deferral decision -- this keeps
// MainViewController from growing further without committing to a full inter-controller split yet.
final class OrderBookPanelBuilder {

    private OrderBookPanelBuilder() {
    }

    // Builds the whole panel: both option books side by side, participants below them, and the order submission
    // form below that -- per docs-reference/ui-sketch-layout.md's Events-tab structure. controller supplies the
    // engine and the handful of display helpers this shares with the rest of MainViewController, rather than
    // duplicating them.
    static VBox build(MainViewController controller, EventStatusDto status, String fixedUsername,
                       Consumer<EventStatusDto> onSuccess) {
        List<OrderBookSnapshotDto> books = status.orderBooks();
        HBox booksRow = new HBox(10,
                buildOptionBookPanel(books.get(0)),
                buildOptionBookPanel(books.get(1)));

        return new VBox(10,
                booksRow,
                new Separator(),
                buildParticipantsSection(status.participants()),
                new Separator(),
                buildOrderSubmissionForm(controller, status, fixedUsername, onSuccess));
    }

    // One option's book: its name, the LAST/BID/ASK/MID/SPREAD stats line, and its resting bids/asks.
    private static VBox buildOptionBookPanel(OrderBookSnapshotDto book) {
        Label statsLine = MainViewController.wrappingLabel("LAST: " + formatNullableMoney(book.lastPrice())
                + "  BID: " + formatNullableMoney(book.bidPrice())
                + "  ASK: " + formatNullableMoney(book.askPrice())
                + "  MID: " + formatNullableMoney(book.midPrice())
                + "  SPREAD: " + formatNullableMoney(book.spread()));

        Label bookHeader = new Label(book.optionName() + " order book");
        bookHeader.getStyleClass().add("section-header");
        return new VBox(6,
                bookHeader,
                statsLine,
                buildOrderListSection("Resting bids:", book.restingBids(), "No resting bids."),
                buildOrderListSection("Resting asks:", book.restingAsks(), "No resting asks."));
    }

    // One side of a book (bids or asks) as a plain list of rows -- matching the existing lightweight style
    // buildTradeHistorySection already uses for read-only display rows, not a ListView with a custom cell factory.
    private static VBox buildOrderListSection(String header, List<OrderDto> orders, String emptyText) {
        Label headerLabel = new Label(header);
        headerLabel.getStyleClass().add("section-header");
        VBox section = new VBox(4, headerLabel);
        if (orders.isEmpty()) {
            section.getChildren().add(new Label(emptyText));
        } else {
            for (OrderDto order : orders) {
                section.getChildren().add(MainViewController.wrappingLabel(formatOrderRow(order)));
            }
        }
        return section;
    }

    private static String formatOrderRow(OrderDto order) {
        return order.username() + ": " + order.side() + " " + MainViewController.formatMoney(order.quantity())
                + " at " + MainViewController.formatDollars(order.price());
    }

    // Full-width participants list: one row per user holding shares of either option, or a placeholder when empty
    // (rare in practice -- the MM already holds both options' initial allocation as soon as the event opens).
    private static VBox buildParticipantsSection(List<ParticipantDto> participants) {
        Label header = new Label("Participants:");
        header.getStyleClass().add("section-header");
        VBox section = new VBox(4, header);
        if (participants.isEmpty()) {
            section.getChildren().add(new Label("No participants yet."));
        } else {
            for (ParticipantDto participant : participants) {
                section.getChildren().add(MainViewController.wrappingLabel(formatParticipantRow(participant)));
            }
        }
        return section;
    }

    private static String formatParticipantRow(ParticipantDto participant) {
        return participant.username()
                + ": option 1 " + MainViewController.formatMoney(participant.optionOneShares())
                + " share(s) (value " + MainViewController.formatDollars(participant.optionOneValue()) + ")"
                + ", option 2 " + MainViewController.formatMoney(participant.optionTwoShares())
                + " share(s) (value " + MainViewController.formatDollars(participant.optionTwoValue()) + ")";
    }

    // Builds the order submission form: a username source (fixed Label from the Users tab, or a ComboBox on the
    // Events tab -- identical pattern to buildParticipateForm), side, option (by name), quantity and price fields,
    // and the Submit button.
    private static VBox buildOrderSubmissionForm(MainViewController controller, EventStatusDto status,
                                                  String fixedUsername, Consumer<EventStatusDto> onSuccess) {
        Node usernameNode;
        Supplier<String> usernameSupplier;
        if (fixedUsername != null) {
            usernameNode = new Label("Trading as: " + fixedUsername);
            usernameSupplier = () -> fixedUsername;
        } else {
            ComboBox<String> usernameComboBox = controller.buildUsernameComboBox();
            usernameNode = usernameComboBox;
            usernameSupplier = () -> usernameComboBox.getSelectionModel().getSelectedItem();
        }

        ComboBox<OrderSide> sideComboBox = new ComboBox<>();
        sideComboBox.getItems().addAll(OrderSide.BUY, OrderSide.SELL);
        sideComboBox.getSelectionModel().selectFirst();

        ComboBox<String> optionComboBox = new ComboBox<>();
        optionComboBox.getItems().addAll(status.optionOneName(), status.optionTwoName());
        optionComboBox.getSelectionModel().selectFirst();

        // Plain TextFields, deliberately not Spinners -- same reason buildParticipateForm's quantity field already
        // avoids Spinner: it reverts to its last valid value on focus-lost, before the Submit click handler runs.
        TextField quantityField = new TextField();
        quantityField.setPromptText("Quantity");
        quantityField.setPrefColumnCount(6);
        TextField priceField = new TextField();
        priceField.setPromptText("Price");
        priceField.setPrefColumnCount(6);

        Button submitButton = new Button("Submit Order");
        submitButton.setOnAction(event -> handleSubmitOrderClick(controller, status.eventId(), usernameSupplier,
                sideComboBox, optionComboBox, quantityField, priceField, onSuccess));

        Label header = new Label("Submit order:");
        header.getStyleClass().add("section-header");
        return new VBox(6, header,
                new HBox(8, usernameNode, sideComboBox, optionComboBox, quantityField, priceField, submitButton));
    }

    // Reads the form's fields exactly as entered; the only ui-level checks are "is something selected" and "does
    // this parse as a number" -- every business rule (price ceiling, non-positive quantity, selling unheld shares,
    // a blocked user) is already enforced server-side by IEngine.submitOrder, so none of it is duplicated here.
    private static void handleSubmitOrderClick(MainViewController controller, int eventId,
                                                Supplier<String> usernameSupplier, ComboBox<OrderSide> sideComboBox,
                                                ComboBox<String> optionComboBox, TextField quantityField,
                                                TextField priceField, Consumer<EventStatusDto> onSuccess) {
        String username = usernameSupplier.get();
        if (username == null || username.isBlank()) {
            controller.showErrorAlert("Invalid input", "Select a user to trade as.");
            return;
        }
        OrderSide side = sideComboBox.getSelectionModel().getSelectedItem();
        if (side == null) {
            controller.showErrorAlert("Invalid input", "Select a side (buy or sell).");
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
            controller.showErrorAlert("Invalid input", "Quantity and price must be numbers.");
            return;
        }

        SubmitOrderRequestDto request = new SubmitOrderRequestDto(username, eventId, optionNumber, side, quantity, price);
        submitOrder(controller, request, onSuccess);
    }

    // Mirrors OrderBookExecutor.roundToCents's own convention -- that one is engine-private and unreachable from
    // gui, so this is a small, deliberate duplication of the same one-line formula, not a shared call.
    private static double roundToCents(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    // Submits the order via the existing IEngine.submitOrder, then lets the caller redraw itself (onSuccess) from
    // the result's own nested eventStatus() -- no second getEventStatus call -- and refreshes both lists, matching
    // every other trading action in this file: a fill moves money between a buyer and seller, so both the events
    // and the users lists can go stale otherwise.
    private static void submitOrder(MainViewController controller, SubmitOrderRequestDto request,
                                     Consumer<EventStatusDto> onSuccess) {
        try {
            OrderResultDto result = controller.engine.submitOrder(request);
            showOrderConfirmation(result);
            onSuccess.accept(result.eventStatus());
            controller.refreshEventsList();
            controller.refreshUsersList();
        } catch (GuessMarketException e) {
            controller.showErrorAlert("Could not submit order", e);
        }
    }

    // Shows the fill breakdown as a confirmation Alert, matching MainViewController.showTradeConfirmation's own
    // pattern. "Total paid"/"Total received" is chosen by side, since OrderResultDto.totalPaid()'s own doc comment
    // defines it as paid for a buy but received for a sell -- always saying "paid" would misdescribe a sell.
    private static void showOrderConfirmation(OrderResultDto result) {
        String totalLabel = result.side() == OrderSide.BUY ? "Total paid" : "Total received";
        // "Filled: 0.00" leading the dialog reads as if nothing happened or something failed, when the order was
        // actually accepted and is genuinely resting in the book -- lead with that explicitly for the zero-fill
        // case only; a partial or full fill's existing breakdown already reads fine on its own.
        String leadIn = result.quantityFilled() == 0 ? "Order submitted and resting -- no immediate match.\n" : "";
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Order Submitted");
        alert.setHeaderText(result.side() + " order for " + result.optionName());
        alert.setContentText(leadIn + "Filled: " + MainViewController.formatMoney(result.quantityFilled())
                + "\nResting: " + MainViewController.formatMoney(result.quantityResting())
                + "\nAverage fill price: " + formatNullableMoney(result.averageFillPrice())
                + "\nCommission: " + MainViewController.formatDollars(result.commissionPaid())
                + "\n" + totalLabel + ": " + MainViewController.formatDollars(result.totalPaid()));
        alert.showAndWait();
    }

    // Renders a nullable stat as "—" rather than a raw null or a misleading 0.0 -- same convention as the rest of
    // this codebase's "No trades yet." / "No resting bids." placeholders for "nothing here yet."
    private static String formatNullableMoney(Double value) {
        return value == null ? "—" : MainViewController.formatDollars(value);
    }
}
