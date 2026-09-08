package gui.components;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.layout.VBox;

import dto.EventStatusDto;
import dto.TradeRecordDto;
import gui.common.Formatters;
import gui.common.Labels;

// Bonus: Graphs. One option-price line per option, plotting that option's own price at each of its own trades in
// chronological order (EventStatusDto.tradeHistory is newest-first, reversed here -- charts read left-to-right,
// oldest-first), or just the header when there's nothing to plot yet -- EventStatusPanelBuilder's own
// "No trades yet." right above already covers that case, so no second placeholder is needed here.
//
// Deliberately NOT a reconstructed two-option LMSR curve: a trade on option A also moves option B's price on the
// shared liquidity curve, but recomputing that would mean gui reaching past IEngine/DTOs into engine.domain.lmsr
// math directly, a layering line this project has never crossed (unlike the one-line roundToCents duplication
// precedent, LMSR math isn't "small enough to duplicate"). Each line reflects prices from that option's own trades
// only -- a disclosed simplification, not a bug.
//
// Uniform across LMSR and Order Book (including mint fills), since Event.addTrade is called identically by every
// trading path (TradeExecutor.participate, OrderBookExecutor.executeFill/mintAgainstOppositeOption) -- no
// method-specific branching needed here. x-axis is trade sequence index, not timestamp/CategoryAxis: a mint's two
// Trades share one LocalDateTime.now() call, so a timestamp-keyed category axis risks real collisions that a
// sequence index can't have.
public final class PriceHistoryChartBuilder {

    private PriceHistoryChartBuilder() {
    }

    public static VBox build(EventStatusDto status) {
        VBox section = new VBox(4, Labels.sectionHeader("Price History:"));

        List<TradeRecordDto> chronological = new ArrayList<>(status.tradeHistory());
        Collections.reverse(chronological);
        if (chronological.isEmpty()) {
            return section;
        }

        NumberAxis xAxis = new NumberAxis();
        xAxis.setLabel("Trade #");
        NumberAxis yAxis = new NumberAxis();
        yAxis.setLabel("Price");
        yAxis.setTickLabelFormatter(Formatters.DOLLAR_AXIS);

        XYChart.Series<Number, Number> optionOneSeries = new XYChart.Series<>();
        optionOneSeries.setName(status.optionOneName());
        addPricePoints(optionOneSeries, chronological, status.optionOneName());
        XYChart.Series<Number, Number> optionTwoSeries = new XYChart.Series<>();
        optionTwoSeries.setName(status.optionTwoName());
        addPricePoints(optionTwoSeries, chronological, status.optionTwoName());

        LineChart<Number, Number> chart = new LineChart<>(xAxis, yAxis);
        // Rebuilt fresh on every panel refresh, never incrementally updated -- animation would look like
        // unwanted "live" motion on every reselect, contradicting the redraw-only-on-refresh design.
        chart.setAnimated(false);
        // Two separate add() calls, not addAll(a, b) -- addAll's varargs form triggers an unchecked generic-array
        // warning for a parameterized Series<Number, Number> (a well-known, harmless Java generics/varargs
        // artifact, but avoiding it costs nothing and keeps the build warning-free).
        chart.getData().add(optionOneSeries);
        chart.getData().add(optionTwoSeries);
        section.getChildren().add(chart);
        return section;
    }

    // Appends one (sequenceIndexWithinThisOption, pricePerShare) point per trade on optionName, in the given
    // chronological order -- shared by both of build()'s two series.
    private static void addPricePoints(XYChart.Series<Number, Number> series, List<TradeRecordDto> chronological, String optionName) {
        int index = 0;
        for (TradeRecordDto trade : chronological) {
            if (trade.optionName().equals(optionName)) {
                index++;
                series.getData().add(new XYChart.Data<>(index, trade.pricePerShare()));
            }
        }
    }
}
