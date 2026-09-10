package gui.common;

import javafx.scene.control.Alert;
import javafx.scene.control.Label;

import dto.OrderResultDto;
import dto.OrderSide;
import dto.TradeConfirmationDto;
import exception.GuessMarketException;

// Every modal message this app shows: engine/task failures and the two trade confirmations. Public and static so
// any module's screens report failures identically without re-implementing the Alert shaping below.
public final class Dialogs {

    private static final int ERROR_DIALOG_WIDTH = 460;

    private Dialogs() {
    }

    // Shows a plain Alert for any background-task or engine-call failure. A GuessMarketException carries a
    // deliberately user-readable message; anything else falls back to its own toString rather than hiding it.
    public static void showError(String headerText, Throwable failure) {
        String message = failure instanceof GuessMarketException
                ? failure.getMessage()
                : String.valueOf(failure);
        showError(headerText, message);
    }

    public static void showError(String headerText, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Error");
        alert.setHeaderText(headerText);

        // The message goes in as a wrapping Label rather than via setContentText: a DialogPane stretches its
        // content to the dialog's own width regardless of the Label's maxWidth (confirmed by direct testing --
        // varying maxWidth between 200 and 400 produced identical results), so pinning the DialogPane's own width
        // is what actually controls the wrap, and the Label just has to be allowed to wrap at all.
        Label content = new Label(message);
        content.setWrapText(true);
        alert.getDialogPane().setContent(content);
        alert.getDialogPane().setPrefWidth(ERROR_DIALOG_WIDTH);
        alert.getDialogPane().setMinWidth(ERROR_DIALOG_WIDTH);

        // Replacing the content also drops the ERROR type's own icon, so it's borrowed from a throwaway WARNING
        // alert (applyCss() forces its skin to build the graphic before it's read).
        Alert warningForGraphic = new Alert(Alert.AlertType.WARNING);
        warningForGraphic.getDialogPane().applyCss();
        alert.getDialogPane().setGraphic(warningForGraphic.getDialogPane().getGraphic());

        alert.showAndWait();
    }

    // Shows the LMSR purchase breakdown (share cost, commission, total) as a simple confirmation Alert.
    public static void showTradeConfirmation(TradeConfirmationDto confirmation) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Purchase Complete");
        alert.setHeaderText("Bought " + Formatters.money(confirmation.shareQuantity()) + " share(s) of " + confirmation.optionName());
        alert.setContentText("Share cost: " + Formatters.dollars(confirmation.shareCost())
                + "\nCommission: " + Formatters.dollars(confirmation.commissionPaid())
                + "\nTotal paid: " + Formatters.dollars(confirmation.totalPaid()));
        alert.showAndWait();
    }

    // Shows a simple deposit confirmation, matching showTradeConfirmation's own pattern -- the caller's own
    // re-render right after this already reflects the new balance in the balance badge, so this dialog only needs
    // to confirm the amount, not repeat the resulting balance.
    public static void showDepositConfirmation(double amount) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Deposit Complete");
        alert.setHeaderText("Deposited " + Formatters.dollars(amount));
        alert.showAndWait();
    }

    // Shows the Order Book fill breakdown, matching showTradeConfirmation's own pattern. "Total paid"/"Total
    // received" is chosen by side, since OrderResultDto.totalPaid()'s own doc comment defines it as paid for a buy
    // but received for a sell -- always saying "paid" would misdescribe a sell.
    public static void showOrderConfirmation(OrderResultDto result) {
        String totalLabel = result.side() == OrderSide.BUY ? "Total paid" : "Total received";
        // "Filled: 0.00" leading the dialog reads as if nothing happened or something failed, when the order was
        // actually accepted and is genuinely resting in the book -- lead with that explicitly for the zero-fill
        // case only; a partial or full fill's existing breakdown already reads fine on its own.
        String leadIn = result.quantityFilled() == 0 ? "Order submitted and resting -- no immediate match.\n" : "";
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Order Submitted");
        alert.setHeaderText(result.side() + " order for " + result.optionName());
        alert.setContentText(leadIn + "Filled: " + Formatters.money(result.quantityFilled())
                + "\nResting: " + Formatters.money(result.quantityResting())
                + "\nAverage fill price: " + Formatters.nullableDollars(result.averageFillPrice())
                + "\nCommission: " + Formatters.dollars(result.commissionPaid())
                + "\n" + totalLabel + ": " + Formatters.dollars(result.totalPaid()));
        alert.showAndWait();
    }
}
