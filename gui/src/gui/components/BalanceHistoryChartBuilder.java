package gui.components;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

import dto.TradeRecordDto;
import dto.UserDetailDto;
import dto.UserEventParticipationDto;
import gui.common.Formatters;
import gui.common.Labels;

// Bonus: Graphs. The user's balance-history section: a header, a chart reconstructed from this user's own merged,
// chronologically-sorted trade history across every event they've touched, and a caption disclosing the
// reconstruction's real accuracy boundary -- see reconstructBalanceSeries's own doc comment for exactly what that
// boundary is. "No purchases yet." placeholder when there's nothing to reconstruct from. x-axis is purchase
// sequence index, same reasoning as PriceHistoryChartBuilder's own x-axis choice.
public final class BalanceHistoryChartBuilder {

    private BalanceHistoryChartBuilder() {
    }

    public static VBox build(UserDetailDto detail) {
        Label header = Labels.sectionHeader("Balance History:");

        // Each participation's own tradeHistory() is newest-first (EngineImpl.toParticipationDto walks the
        // event's chronological trade list in reverse) -- reversed back to true chronological here BEFORE
        // merging. That per-event order is reliable (real insertion order, immune to timestamp ties), which
        // matters because two same-event trades can share one LocalDateTime.now() value on fast successive
        // calls -- List.sort is stable, so sorting the newest-first lists directly by timestamp would leave
        // tied same-event trades in their pre-sort (i.e. backwards) order instead of fixing it. Found and fixed
        // via this exact scenario during verification, not assumed safe.
        List<TradeRecordDto> merged = new ArrayList<>();
        for (UserEventParticipationDto participation : detail.activeParticipations()) {
            List<TradeRecordDto> chronological = new ArrayList<>(participation.tradeHistory());
            Collections.reverse(chronological);
            merged.addAll(chronological);
        }
        merged.sort(Comparator.comparing(TradeRecordDto::timestamp));
        if (merged.isEmpty()) {
            return new VBox(4, header, new Label("No purchases yet."));
        }

        double[] balances = reconstructBalanceSeries(merged, detail.balance());

        NumberAxis xAxis = new NumberAxis();
        xAxis.setLabel("Purchase #");
        NumberAxis yAxis = new NumberAxis();
        yAxis.setLabel("Balance");
        yAxis.setTickLabelFormatter(Formatters.DOLLAR_AXIS);

        XYChart.Series<Number, Number> series = new XYChart.Series<>();
        for (int i = 0; i < balances.length; i++) {
            series.getData().add(new XYChart.Data<>(i + 1, balances[i]));
        }

        LineChart<Number, Number> chart = new LineChart<>(xAxis, yAxis);
        chart.setAnimated(false); // same reasoning as PriceHistoryChartBuilder's chart
        chart.setLegendVisible(false); // only one series -- a legend would just repeat "Balance" for no benefit
        chart.getData().add(series);

        Label caption = Labels.wrapping("Reconstructed from recorded purchases; other balance-affecting events "
                + "(payouts, subsidies, sale proceeds) aren't reflected and may shift earlier points.");
        return new VBox(4, header, chart, caption);
    }

    // Reconstructs "balance immediately after each trade" by walking the chronological trade list BACKWARD from
    // the known-true current balance, undoing each trade's own totalPaid debit as we go. User.balance has no
    // history anywhere -- this is a read-only, render-time-only reconstruction, never a new stored field.
    //
    // The LAST (most recent) point is always exactly correct, by construction. Everything before it is only
    // correct if no unrecorded balance-changing event happened in between -- and several real ones exist that
    // never create a buyer-attributed Trade at all: LMSR close-time winner payouts and MM subsidy debit/leftover
    // return, Order Book MM initial-allocation debit and close-time holder payouts, and an Order Book seller's
    // own proceeds in someone else's fill (that Trade's buyerUsername is the other party, so it never surfaces
    // in the seller's own participation view). Such an event does NOT create a local "flat spot" in the graph --
    // it bakes a constant offset into that point and propagates it backward through EVERY earlier point too,
    // since each further backward step only adds a correct trade amount on top of an already-wrong base (traced
    // by hand against a worked example before writing this: start $1000, buy $100 -> true $900, an unrecorded
    // $50 credit -> true $950, buy $80 -> true/anchor $870; walking backward from $870 undoes the $80 buy to
    // $950, labeled "after the first buy" -- but the true value there was $900, a $50 error that would carry
    // through identically to any point still earlier). Multiple unrecorded events compound. Only the segment
    // from the most recent unrecorded event up to the anchor is guaranteed correct -- disclosed to the user via
    // an on-screen caption above, not just this comment. A fully complete reconstruction would need an
    // engine-side balance ledger; correctly out of scope for a bonus feature.
    private static double[] reconstructBalanceSeries(List<TradeRecordDto> chronological, double currentBalance) {
        double[] balances = new double[chronological.size()];
        double runningBalance = currentBalance;
        for (int i = chronological.size() - 1; i >= 0; i--) {
            balances[i] = runningBalance;
            runningBalance += chronological.get(i).totalPaid();
        }
        return balances;
    }
}
