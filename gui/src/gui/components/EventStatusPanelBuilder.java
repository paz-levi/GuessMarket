package gui.components;

import java.util.List;

import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.layout.VBox;

import dto.EventStatusDto;
import dto.TradeRecordDto;
import dto.TradingMethod;
import gui.common.Formatters;
import gui.common.Labels;

// The read-only "what is this event's state right now" block: title, market maker, both options, account state,
// winner (if closed), trade history, and the price-history chart. Shared by the Events tab's full detail panel
// and the Users tab's read-only per-event sub-panel -- and public/static so a separate module's screens can
// render the identical block from the same DTO.
public final class EventStatusPanelBuilder {

    private EventStatusPanelBuilder() {
    }

    // Appends the whole status display to the given container.
    // Only the LMSR-specific "price" concept is hidden for an Order Book event (optionOnePrice/optionTwoPrice are
    // 0.0 there -- see EngineImpl.toStatusDto's own comment on why; the real per-option price picture lives in
    // OrderBookPanelBuilder's book display instead). Everything else here stays real and meaningful for BOTH
    // methods and is shown either way: shares outstanding (the total ever issued for that option), MM account
    // balance, and total commission collected -- the event account still holds the MM's initial funding plus every
    // fill's accumulated commission for an Order Book event too, and nothing else currently displays either number.
    public static void append(VBox container, EventStatusDto status) {
        boolean isLmsr = status.tradingMethod() == TradingMethod.LMSR;
        Label titleLine = Labels.wrapping(status.eventName() + "  —  " + Formatters.status(status.status()));
        titleLine.getStyleClass().add("section-header");
        container.getChildren().addAll(
                titleLine,
                Labels.wrapping("Market Maker: " + status.marketMakerUsername()),
                Labels.wrapping(Formatters.optionLine(status.optionOneName(), status.optionOnePrice(), status.optionOneShares(), isLmsr)),
                Labels.wrapping(Formatters.optionLine(status.optionTwoName(), status.optionTwoPrice(), status.optionTwoShares(), isLmsr)),
                Labels.wrapping("Market maker balance: " + Formatters.dollars(status.marketMakerBalance())),
                Labels.wrapping("Total commission collected: " + Formatters.dollars(status.totalCommissionCollected()))
        );
        if (status.winningOptionName() != null) {
            container.getChildren().add(Labels.wrapping("Winner: " + status.winningOptionName()));
        }
        container.getChildren().add(new Separator());
        container.getChildren().add(buildTradeHistorySection(status.tradeHistory()));
        container.getChildren().add(PriceHistoryChartBuilder.build(status));
    }

    // Builds the trade-history block: a header plus one row per trade (already newest-first, per EventStatusDto's own contract), or a placeholder when empty.
    private static VBox buildTradeHistorySection(List<TradeRecordDto> tradeHistory) {
        VBox section = new VBox(4, Labels.sectionHeader("Trade history:"));
        if (tradeHistory.isEmpty()) {
            section.getChildren().add(new Label("No trades yet."));
        } else {
            for (TradeRecordDto trade : tradeHistory) {
                section.getChildren().add(Labels.wrapping(trade.optionName() + ": " + Formatters.money(trade.quantity())
                        + " share(s) at " + Formatters.dollars(trade.pricePerShare())
                        + ", commission " + Formatters.dollars(trade.commissionPaid())
                        + ", total " + Formatters.dollars(trade.totalPaid())
                        + "  (" + Formatters.tradeTimestamp(trade.timestamp()) + ")"));
            }
        }
        return section;
    }
}
