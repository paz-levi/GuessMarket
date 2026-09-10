package gui.tabs;

import java.util.function.Function;

import javafx.fxml.FXML;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Separator;
import javafx.scene.control.SplitPane;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import dto.CommissionMode;
import dto.EventFilterDto;
import dto.EventStatus;
import dto.EventStatusDto;
import dto.EventSummaryDto;
import dto.TradingMethod;
import engine.IEngine;
import gui.common.Async;
import gui.common.Dialogs;
import gui.common.Formatters;
import gui.components.EventActionsPanelBuilder;
import gui.components.EventStatusPanelBuilder;

// The Events tab: the filtered event list on the left, one event's full details plus its status-appropriate action
// controls on the right. Owns only what belongs to this tab -- everything shared with the Users tab lives in
// gui.components, and anything that changes the OTHER tab's data goes through the TabCoordinator rather than
// touching it directly.
public class EventsTabController {

    @FXML
    private SplitPane eventsSplitPane;

    @FXML
    private Label eventsNoFileLabel;

    @FXML
    private ComboBox<TradingMethod> methodFilterComboBox;

    @FXML
    private ComboBox<EventStatus> statusFilterComboBox;

    @FXML
    private ComboBox<CommissionMode> commissionFilterComboBox;

    @FXML
    private ListView<EventSummaryDto> eventsListView;

    @FXML
    private VBox eventDetailsBox;

    private IEngine engine;
    private TabCoordinator coordinator;

    // Null under the plain in-process launch (no login screen) -- see EventActionsPanelBuilder.build's own
    // fixedUsername doc for what that means for the action controls rendered below.
    private String username;

    // Both injected by the hosting shell right after the FXML tree is built -- never during initialize(), which
    // runs before the shell has either of them. Nothing in initialize() touches the engine, so that ordering is safe.
    public void setEngine(IEngine engine) {
        this.engine = engine;
    }

    public void setCoordinator(TabCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    // Set by the hosting shell once a real logged-in username exists (Exercise 3's ClientApp only); left null under
    // the plain in-process launch.
    public void setUsername(String username) {
        this.username = username;
    }

    // Wires this tab's list rendering, row selection, and filters; called automatically by FXMLLoader once all
    // @FXML fields are injected.
    @FXML
    private void initialize() {
        eventsListView.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(EventSummaryDto event, boolean empty) {
                super.updateItem(event, empty);
                setText(empty || event == null ? null : Formatters.eventSummary(event));
            }
        });
        eventsListView.getSelectionModel().selectedItemProperty().addListener((observable, oldSelection, newSelection) -> {
            if (newSelection != null) {
                showEventDetails(newSelection.eventName());
            }
        });

        // Populate every filter ComboBox -- including its internal selectFirst() default-to-"All" -- BEFORE attaching
        // any selectedItemProperty listener below. selectFirst() only notifies listeners already registered at the
        // moment it runs; since none exist yet during population, the initial "All" selection cannot trigger
        // refreshEventsList() at startup, before any file is loaded.
        populateFilterComboBox(methodFilterComboBox, TradingMethod.values(), "All", Formatters::tradingMethod);
        populateFilterComboBox(statusFilterComboBox, EventStatus.values(), "All", Formatters::status);
        populateFilterComboBox(commissionFilterComboBox, CommissionMode.values(), "All", Formatters::commissionMode);

        methodFilterComboBox.getSelectionModel().selectedItemProperty().addListener((observable, oldSelection, newSelection) -> refreshEventsList());
        statusFilterComboBox.getSelectionModel().selectedItemProperty().addListener((observable, oldSelection, newSelection) -> refreshEventsList());
        commissionFilterComboBox.getSelectionModel().selectedItemProperty().addListener((observable, oldSelection, newSelection) -> refreshEventsList());
    }

    // Swaps this tab's "No file loaded" placeholder for its real content (filter bar included, so nothing in it
    // is interactable before a file loads at all). Idempotent -- safe to call on every successful load, not just
    // the first, so no extra "have we loaded before" state is needed anywhere.
    public void revealLoadedContent() {
        eventsSplitPane.setVisible(true);
        eventsSplitPane.setManaged(true);
        eventsNoFileLabel.setVisible(false);
        eventsNoFileLabel.setManaged(false);
    }

    // Re-reads the event list from the engine, filtered by the three filter ComboBoxes' current selections, and
    // refreshes the list view; called right after a successful load, whenever a filter selection changes, and
    // whenever any action anywhere reports that event data changed (via the coordinator). Every IEngine call is a
    // real network round-trip once this is backed by HttpEngineClient, so it runs on a background Task -- see
    // gui.common.Async.
    public void refreshEventsList() {
        EventFilterDto filter = new EventFilterDto(
                methodFilterComboBox.getSelectionModel().getSelectedItem(),
                statusFilterComboBox.getSelectionModel().getSelectedItem(),
                commissionFilterComboBox.getSelectionModel().getSelectedItem());
        Async.run(() -> engine.listEvents(filter),
                events -> eventsListView.getItems().setAll(events),
                failure -> Dialogs.showError("Could not list events", failure));
    }

    // Looks up one event's full status and renders it in the right-hand details panel; called whenever the Events
    // list selection changes. Runs on a background Task -- see refreshEventsList's own note above.
    private void showEventDetails(String eventName) {
        Async.run(() -> engine.getEventStatus(eventName),
                this::renderEventDetails,
                failure -> Dialogs.showError("Could not load event details", failure));
    }

    // Rebuilds the details panel's content from scratch: the read-only status display plus the action controls.
    private void renderEventDetails(EventStatusDto status) {
        eventDetailsBox.getChildren().clear();
        EventStatusPanelBuilder.append(eventDetailsBox, status);
        eventDetailsBox.getChildren().add(new Separator());
        // The action control is driven by status: only ever show the one thing that can actually succeed right now.
        // fixedUsername is the real logged-in username under Exercise 3's client, or null under the plain in-process
        // launch (no login screen) -- EventActionsPanelBuilder falls back to a picker only in the null case.
        // showOpenControl=true: the Events tab is always where a NOT_STARTED event's Open form lives, regardless of
        // whether a session username is set -- kept as its own parameter, separate from fixedUsername, specifically
        // so a real logged-in username here can never be mistaken for "we're on the Users tab" (see
        // EventActionsPanelBuilder.build's own doc for the bug this fixes).
        eventDetailsBox.getChildren().add(
                EventActionsPanelBuilder.build(engine, coordinator, status, username, true, this::renderEventDetails));
    }

    // Populates one filter ComboBox: null ("All") as the first item, then every value of the enum, rendered through
    // toLabel -- the SAME label the event list's own rows already use for that field, so the filter never shows
    // different wording than what it's actually filtering by. Defaults to "All" via selectFirst() rather than
    // select(null): JavaFX commonly special-cases select(null) as "clear the selection" rather than "select the item
    // whose value is null," which would leave the box showing blank instead of "All".
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
}
