package gui;

import java.io.File;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Separator;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.StringConverter;

import dto.CommissionMode;
import dto.EventFilterDto;
import dto.EventStatus;
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

    // Trade-history rows show hour:minute only, not the raw LocalDateTime's full ISO-8601-with-microseconds --
    // matching every other clean-formatting convention already used in this app (e.g. 2-decimal money).
    private static final DateTimeFormatter TRADE_TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    // Chart y-axis tick labels, formatted with the app's own $ convention (formatMoney's 2-decimal rule) instead
    // of NumberAxis's own default numeric formatting.
    private static final StringConverter<Number> DOLLAR_AXIS_FORMATTER = new StringConverter<>() {
        @Override
        public String toString(Number value) {
            return "$" + formatMoney(value.doubleValue());
        }

        @Override
        public Number fromString(String string) {
            throw new UnsupportedOperationException(); // axis tick labels are never parsed back
        }
    };

    @FXML
    private Button loadFileButton;

    @FXML
    private ComboBox<String> colorSchemeComboBox;

    @FXML
    private Label filePathLabel;

    @FXML
    private ProgressIndicator loadProgressIndicator;

    @FXML
    private ComboBox<TradingMethod> methodFilterComboBox;

    @FXML
    private ComboBox<EventStatus> statusFilterComboBox;

    @FXML
    private ComboBox<CommissionMode> commissionFilterComboBox;

    @FXML
    private Button createEventButton;

    @FXML
    private ListView<EventSummaryDto> eventsListView;

    @FXML
    private VBox eventDetailsBox;

    @FXML
    private SplitPane eventsSplitPane;

    @FXML
    private Label eventsNoFileLabel;

    @FXML
    private ListView<UserSummaryDto> usersListView;

    @FXML
    private VBox userDetailsBox;

    @FXML
    private SplitPane usersSplitPane;

    @FXML
    private Label usersNoFileLabel;

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
        createEventButton.setOnAction(event -> handleCreateEventClick());

        // Skins bonus: three schemes, defaulting to "Default" -- selected BY VALUE, not selectFirst()/by index,
        // so correctness never depends on item order (the filter ComboBoxes elsewhere use selectFirst(), which
        // would be unsafe here specifically -- the bonus must launch "off," never on one of the two new schemes,
        // regardless of alphabetical or any other incidental ordering).
        colorSchemeComboBox.getItems().addAll("Default", "Dark", "High Contrast");
        colorSchemeComboBox.getSelectionModel().select("Default");
        colorSchemeComboBox.getSelectionModel().selectedItemProperty().addListener((observable, oldScheme, newScheme) -> applyColorScheme(newScheme));
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

        // Populate every filter ComboBox -- including its internal selectFirst() default-to-"All" -- BEFORE attaching
        // any selectedItemProperty listener below. selectFirst() only notifies listeners already registered at the
        // moment it runs; since none exist yet during population, the initial "All" selection cannot trigger
        // refreshEventsList() at startup, before any file is loaded.
        populateFilterComboBox(methodFilterComboBox, TradingMethod.values(), "All", MainViewController::formatTradingMethod);
        populateFilterComboBox(statusFilterComboBox, EventStatus.values(), "All", MainViewController::formatStatus);
        populateFilterComboBox(commissionFilterComboBox, CommissionMode.values(), "All", MainViewController::formatCommissionMode);

        methodFilterComboBox.getSelectionModel().selectedItemProperty().addListener((observable, oldSelection, newSelection) -> refreshEventsList());
        statusFilterComboBox.getSelectionModel().selectedItemProperty().addListener((observable, oldSelection, newSelection) -> refreshEventsList());
        commissionFilterComboBox.getSelectionModel().selectedItemProperty().addListener((observable, oldSelection, newSelection) -> refreshEventsList());

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

    // Swaps the Scene's active stylesheet to the chosen scheme -- Scene.getStylesheets() is observable, so
    // Scene/Parent re-run CSS resolution across the whole existing scene graph on the next pulse, not just newly
    // created nodes; this is standard JavaFX runtime theme switching, not the app's own custom mechanism.
    // setAll(...) replaces the list's entire contents in one change, so exactly one scheme is ever active -- never
    // a multi-file cascade where the "active" theme would depend on list order. The Scene is reached lazily off
    // the ComboBox itself (colorSchemeComboBox.getScene()), the same pattern handleLoadFile already uses for its
    // owner Window, rather than threading a Scene reference in from GuessMarketApp.
    private void applyColorScheme(String scheme) {
        String resource = switch (scheme) {
            case "Dark" -> "styles-dark.css";
            case "High Contrast" -> "styles-high-contrast.css";
            default -> "styles.css";
        };
        colorSchemeComboBox.getScene().getStylesheets().setAll(
                MainViewController.class.getResource(resource).toExternalForm());
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
            revealLoadedContent();
            refreshEventsList();
            refreshUsersList();
        });
        loadTask.setOnFailed(event -> showErrorAlert("Could not load the events file", loadTask.getException()));

        Thread thread = new Thread(loadTask, "load-events-file");
        thread.setDaemon(true);
        thread.start();
    }

    // Opens the "Create Event" dialog. A plain static-method helper class (CreateEventDialogBuilder) builds and
    // drives the whole form, matching OrderBookPanelBuilder's own role/reasoning; on success it already refreshes
    // the Events list itself, so this only needs to show the newly created event's details afterward.
    private void handleCreateEventClick() {
        CreateEventDialogBuilder.show(this, status -> showEventDetails(status.eventId()));
    }

    // Swaps each tab's "No file loaded" placeholder for its real content (filter bar included, so nothing in it
    // is interactable before a file loads at all). Idempotent -- safe to call on every successful load, not just
    // the first, so no extra "have we loaded before" state is needed anywhere.
    private void revealLoadedContent() {
        eventsSplitPane.setVisible(true);
        eventsSplitPane.setManaged(true);
        eventsNoFileLabel.setVisible(false);
        eventsNoFileLabel.setManaged(false);
        usersSplitPane.setVisible(true);
        usersSplitPane.setManaged(true);
        usersNoFileLabel.setVisible(false);
        usersNoFileLabel.setManaged(false);
    }

    // Re-reads the event list from the engine, filtered by the three filter ComboBoxes' current selections, and
    // refreshes the Events tab; called right after a successful load, and again whenever a filter selection changes
    // or a trading action completes. Package-private: also called from OrderBookPanelBuilder after order submission.
    void refreshEventsList() {
        try {
            EventFilterDto filter = new EventFilterDto(
                    methodFilterComboBox.getSelectionModel().getSelectedItem(),
                    statusFilterComboBox.getSelectionModel().getSelectedItem(),
                    commissionFilterComboBox.getSelectionModel().getSelectedItem());
            List<EventSummaryDto> events = engine.listEvents(filter);
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
        Label balanceLabel = wrappingLabel("Balance: " + formatDollars(detail.balance()) + (detail.blocked() ? "  (BLOCKED)" : ""));
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

        Label participationHeader = new Label("Events Participation / Owner:");
        participationHeader.getStyleClass().add("section-header");
        Label singleEventHeader = new Label("Single event details and trade:");
        singleEventHeader.getStyleClass().add("section-header");

        userDetailsBox.getChildren().setAll(
                balanceBadge,
                buildBalanceHistorySection(detail),
                participationHeader,
                participationListView,
                new Separator(),
                singleEventHeader,
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

    // Bonus: Graphs. Builds the user's balance-history section: a header, a chart reconstructed from this user's
    // own merged, chronologically-sorted trade history across every event they've touched, and a caption
    // disclosing the reconstruction's real accuracy boundary -- see reconstructBalanceSeries's own doc comment
    // for exactly what that boundary is. "No purchases yet." placeholder when there's nothing to reconstruct
    // from. x-axis is purchase sequence index, same reasoning as buildPriceHistorySection's own x-axis choice.
    private static VBox buildBalanceHistorySection(UserDetailDto detail) {
        Label header = new Label("Balance History:");
        header.getStyleClass().add("section-header");

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
        yAxis.setTickLabelFormatter(DOLLAR_AXIS_FORMATTER);

        XYChart.Series<Number, Number> series = new XYChart.Series<>();
        for (int i = 0; i < balances.length; i++) {
            series.getData().add(new XYChart.Data<>(i + 1, balances[i]));
        }

        LineChart<Number, Number> chart = new LineChart<>(xAxis, yAxis);
        chart.setAnimated(false); // same reasoning as buildPriceHistorySection's chart
        chart.setLegendVisible(false); // only one series -- a legend would just repeat "Balance" for no benefit
        chart.getData().add(series);

        Label caption = wrappingLabel("Reconstructed from recorded purchases; other balance-affecting events "
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
    // an on-screen caption (buildBalanceHistorySection), not just this comment. A fully complete reconstruction
    // would need an engine-side balance ledger; correctly out of scope for a bonus feature.
    private static double[] reconstructBalanceSeries(List<TradeRecordDto> chronological, double currentBalance) {
        double[] balances = new double[chronological.size()];
        double runningBalance = currentBalance;
        for (int i = chronological.size() - 1; i >= 0; i--) {
            balances[i] = runningBalance;
            runningBalance += chronological.get(i).totalPaid();
        }
        return balances;
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
                    : new VBox(wrappingLabel("This event has not been opened yet — its market maker can open it from the Events tab."));
            case ACTIVE -> buildActiveControls(status, fixedUsername, onSuccess);
            case CLOSED -> new VBox(wrappingLabel("This event is closed and no longer accepts trades."));
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

        Label header = new Label("Close this event (market maker only):");
        header.getStyleClass().add("section-header");
        return new VBox(6, header, new HBox(8, usernameComboBox, winningOptionComboBox, closeButton));
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

        Label header = new Label("Open this event (market maker only):");
        header.getStyleClass().add("section-header");
        return new VBox(6, header, new HBox(8, usernameComboBox, openButton));
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
        Label titleLine = wrappingLabel(status.eventName() + "  —  " + formatStatus(status.status()));
        titleLine.getStyleClass().add("section-header");
        container.getChildren().addAll(
                titleLine,
                wrappingLabel("Market Maker: " + status.marketMakerUsername()),
                wrappingLabel(formatOptionLine(status.optionOneName(), status.optionOnePrice(), status.optionOneShares(), isLmsr)),
                wrappingLabel(formatOptionLine(status.optionTwoName(), status.optionTwoPrice(), status.optionTwoShares(), isLmsr)),
                wrappingLabel("Market maker balance: " + formatDollars(status.marketMakerBalance())),
                wrappingLabel("Total commission collected: " + formatDollars(status.totalCommissionCollected()))
        );
        if (status.winningOptionName() != null) {
            container.getChildren().add(wrappingLabel("Winner: " + status.winningOptionName()));
        }
        container.getChildren().add(new Separator());
        container.getChildren().add(buildTradeHistorySection(status.tradeHistory()));
        container.getChildren().add(buildPriceHistorySection(status));
    }

    // One option's summary line: "price X, shares Y" for LMSR (the curve-price concept is real there), "shares Y"
    // alone for Order Book (price is meaningless there, always 0.0) -- shares outstanding stays meaningful either way.
    private static String formatOptionLine(String optionName, double price, double shares, boolean isLmsr) {
        return optionName + ": " + (isLmsr ? "price " + formatDollars(price) + ", " : "") + "shares " + formatMoney(shares);
    }

    // Builds the trade-history block: a header plus one row per trade (already newest-first, per EventStatusDto's own contract), or a placeholder when empty.
    private static VBox buildTradeHistorySection(List<TradeRecordDto> tradeHistory) {
        Label header = new Label("Trade history:");
        header.getStyleClass().add("section-header");
        VBox section = new VBox(4, header);
        if (tradeHistory.isEmpty()) {
            section.getChildren().add(new Label("No trades yet."));
        } else {
            for (TradeRecordDto trade : tradeHistory) {
                section.getChildren().add(wrappingLabel(trade.optionName() + ": " + formatMoney(trade.quantity())
                        + " share(s) at " + formatDollars(trade.pricePerShare())
                        + ", commission " + formatDollars(trade.commissionPaid())
                        + ", total " + formatDollars(trade.totalPaid())
                        + "  (" + trade.timestamp().format(TRADE_TIMESTAMP_FORMAT) + ")"));
            }
        }
        return section;
    }

    // Bonus: Graphs. Builds the event's price-history section: a header plus one line per option, plotting that
    // option's own price at each of its own trades in chronological order (tradeHistory is newest-first, reversed
    // here -- charts read left-to-right, oldest-first), or just the header when there's nothing to plot yet --
    // buildTradeHistorySection's own "No trades yet." right above already covers that case, so no second
    // placeholder is needed here. Deliberately NOT a reconstructed two-option LMSR curve: a trade on option A
    // also moves option B's price on the shared liquidity curve, but recomputing that would mean gui reaching
    // past IEngine/DTOs into engine.domain.lmsr math directly, a layering line this project has never crossed
    // (unlike the one-line roundToCents duplication precedent, LMSR math isn't "small enough to duplicate").
    // Each line reflects prices from that option's own trades only -- a disclosed simplification, not a bug.
    // Uniform across LMSR and Order Book (including mint fills), since Event.addTrade is called identically by
    // every trading path (TradeExecutor.participate, OrderBookExecutor.executeFill/mintAgainstOppositeOption) --
    // no method-specific branching needed here. x-axis is trade sequence index, not timestamp/CategoryAxis: a
    // mint's two Trades share one LocalDateTime.now() call, so a timestamp-keyed category axis risks real
    // collisions that a sequence index can't have.
    private static VBox buildPriceHistorySection(EventStatusDto status) {
        Label header = new Label("Price History:");
        header.getStyleClass().add("section-header");

        List<TradeRecordDto> chronological = new ArrayList<>(status.tradeHistory());
        Collections.reverse(chronological);
        if (chronological.isEmpty()) {
            return new VBox(4, header);
        }

        NumberAxis xAxis = new NumberAxis();
        xAxis.setLabel("Trade #");
        NumberAxis yAxis = new NumberAxis();
        yAxis.setLabel("Price");
        yAxis.setTickLabelFormatter(DOLLAR_AXIS_FORMATTER);

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
        return new VBox(4, header, chart);
    }

    // Appends one (sequenceIndexWithinThisOption, pricePerShare) point per trade on optionName, in the given
    // chronological order -- shared by both of buildPriceHistorySection's two series.
    private static void addPricePoints(XYChart.Series<Number, Number> series, List<TradeRecordDto> chronological, String optionName) {
        int index = 0;
        for (TradeRecordDto trade : chronological) {
            if (trade.optionName().equals(optionName)) {
                index++;
                series.getData().add(new XYChart.Data<>(index, trade.pricePerShare()));
            }
        }
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

        Label header = new Label("Participate:");
        header.getStyleClass().add("section-header");
        return new VBox(6, header, new HBox(8, usernameNode, optionComboBox, quantityField, buyButton));
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

    // Populates one Events-list filter ComboBox: null ("All") as the first item, then every value of the enum,
    // rendered through toLabel -- the SAME label the event list's own rows already use for that field, so the
    // filter never shows different wording than what it's actually filtering by. Defaults to "All" via
    // selectFirst() rather than select(null): JavaFX commonly special-cases select(null) as "clear the selection"
    // rather than "select the item whose value is null," which would leave the box showing blank instead of "All".
    private static <T> void populateFilterComboBox(ComboBox<T> comboBox, T[] values, String allLabel,
                                                     Function<T, String> toLabel) {
        comboBox.getItems().add(null);
        comboBox.getItems().addAll(values);
        comboBox.setConverter(new StringConverter<T>() {
            @Override
            public String toString(T value) {
                return value == null ? allLabel : toLabel.apply(value);
            }

            @Override
            public T fromString(String string) {
                throw new UnsupportedOperationException(); // not editable, never needs parsing back
            }
        });
        comboBox.getSelectionModel().selectFirst(); // index 0 = the null/"All" item just inserted above
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
        alert.setContentText("Share cost: " + formatDollars(confirmation.shareCost())
                + "\nCommission: " + formatDollars(confirmation.commissionPaid())
                + "\nTotal paid: " + formatDollars(confirmation.totalPaid()));
        alert.showAndWait();
    }

    // Formats one event's summary row: name, status, trading method, commission rate/mode — every field EventSummaryDto already carries.
    private static String formatEventSummary(EventSummaryDto event) {
        return event.eventName() + "  —  " + formatStatus(event.status()) + "  —  " + formatTradingMethod(event.tradingMethod())
                + "  —  " + event.commissionRate() + "% " + formatCommissionMode(event.commissionMode());
    }

    // Small presentation helper, matching ui.Main's own formatCommissionMode wording.
    // Package-private (not private): CreateEventDialogBuilder, a separate class in this same package, reuses these
    // two for its own commission-mode/trading-method ComboBoxes rather than duplicating the display strings.
    static String formatCommissionMode(CommissionMode mode) {
        return mode == CommissionMode.ON_PURCHASE ? "On Purchase" : "On Close";
    }

    // Small presentation helpers, same pattern as formatCommissionMode above: raw enum names ("NOT_STARTED",
    // "ORDER_BOOK") aren't something an end user should see.
    private static String formatStatus(EventStatus status) {
        return switch (status) {
            case NOT_STARTED -> "Not Started";
            case ACTIVE -> "Active";
            case CLOSED -> "Closed";
        };
    }

    static String formatTradingMethod(TradingMethod method) {
        return method == TradingMethod.ORDER_BOOK ? "Order Book" : "LMSR"; // LMSR is a domain term, not an abbreviation to expand
    }

    // Formats one user's summary row: username, balance, and a blocked marker when applicable — every field UserSummaryDto already carries.
    private static String formatUserSummary(UserSummaryDto user) {
        return user.username() + "  —  " + formatDollars(user.balance()) + (user.blocked() ? "  (BLOCKED)" : "");
    }

    // Formats one row of a user's events-participation list: event name, status, trading method.
    private static String formatParticipation(UserEventParticipationDto participation) {
        return participation.eventName() + "  —  " + formatStatus(participation.eventStatus())
                + "  —  " + formatTradingMethod(participation.tradingMethod());
    }

    // Pins Locale.US so money/share values can't silently print a comma on a non-English-default JVM — same discipline ui.Main already applies.
    // Package-private: also called from OrderBookPanelBuilder, for the same values (quantities and prices alike).
    static String formatMoney(double value) {
        return String.format(Locale.US, "%.2f", value);
    }

    // Prefixes formatMoney's own 2-decimal formatting with the spec's own currency notation ($) -- kept separate
    // from formatMoney itself, which is also used for bare share quantities that must never show a $.
    // Package-private: also called from OrderBookPanelBuilder, for the same money values.
    static String formatDollars(double value) {
        return "$" + formatMoney(value);
    }

    // Package-private: also called from OrderBookPanelBuilder. A plain Label's minimum width equals its full
    // unwrapped text width, so a long line simply clips once its container is squeezed narrower by a resize --
    // wrapText lets it reflow onto more lines instead, which CLAUDE.md's resize rule requires.
    static Label wrappingLabel(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        return label;
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

        // Plain setContentText's default Label sizing can truncate a realistic one-line validation message with
        // an ellipsis. Round 1 tried setMaxWidth(Double.MAX_VALUE) at a 420px dialog width and it still truncated
        // for a genuinely long message. Verified directly (a harness that varies the Label's own maxWidth and
        // reads back its real laid-out bounds): DialogPane's own content region stretches its content to the full
        // dialog width regardless of the Label's own maxWidth setting -- so the Label's maxWidth is irrelevant
        // either way, and the width that actually matters is the DialogPane's own. setWrapText(true) plus a
        // wide-enough dialog width is sufficient on its own.
        Label content = new Label(message);
        content.setWrapText(true);
        alert.getDialogPane().setContent(content);
        alert.getDialogPane().setPrefWidth(460);
        alert.getDialogPane().setMinWidth(460);

        // Visual only: borrow WARNING's default graphic (triangle) instead of ERROR's (red X), while keeping every
        // other ERROR semantic (title, buttons, AlertType itself) unchanged. The default graphic is resolved from
        // CSS at skin-creation time, which normally doesn't happen until a dialog is actually shown -- applyCss()
        // forces it to resolve now, on a throwaway Alert that's never shown, so getGraphic() doesn't return null.
        Alert warningForGraphic = new Alert(Alert.AlertType.WARNING);
        warningForGraphic.getDialogPane().applyCss();
        alert.getDialogPane().setGraphic(warningForGraphic.getDialogPane().getGraphic());

        alert.showAndWait();
    }
}
