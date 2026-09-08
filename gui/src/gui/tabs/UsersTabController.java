package gui.tabs;

import java.util.List;

import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Separator;
import javafx.scene.control.SplitPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import dto.EventStatusDto;
import dto.UserDetailDto;
import dto.UserEventParticipationDto;
import dto.UserSummaryDto;
import engine.IEngine;
import exception.GuessMarketException;
import gui.common.Dialogs;
import gui.common.Formatters;
import gui.common.Labels;
import gui.components.BalanceHistoryChartBuilder;
import gui.components.EventActionsPanelBuilder;
import gui.components.EventStatusPanelBuilder;

// The Users tab: the user list on the left, one user's balance/history/participations plus a per-event sub-panel
// on the right. Owns only what belongs to this tab -- everything shared with the Events tab lives in
// gui.components, and anything that changes the OTHER tab's data goes through the TabCoordinator rather than
// touching it directly.
public class UsersTabController {

    private static final int PARTICIPATION_LIST_HEIGHT = 150;

    @FXML
    private SplitPane usersSplitPane;

    @FXML
    private Label usersNoFileLabel;

    @FXML
    private ListView<UserSummaryDto> usersListView;

    @FXML
    private VBox userDetailsBox;

    private IEngine engine;
    private TabCoordinator coordinator;

    // Both injected by the hosting shell right after the FXML tree is built -- never during initialize(), which
    // runs before the shell has either of them. Nothing in initialize() touches the engine, so that ordering is safe.
    public void setEngine(IEngine engine) {
        this.engine = engine;
    }

    public void setCoordinator(TabCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    // Wires this tab's list rendering and row selection; called automatically by FXMLLoader once all @FXML fields
    // are injected.
    @FXML
    private void initialize() {
        usersListView.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(UserSummaryDto user, boolean empty) {
                super.updateItem(user, empty);
                setText(empty || user == null ? null : Formatters.userSummary(user));
            }
        });
        usersListView.getSelectionModel().selectedItemProperty().addListener((observable, oldSelection, newSelection) -> {
            if (newSelection != null) {
                showUserDetails(newSelection.username());
            }
        });
    }

    // Swaps this tab's "No file loaded" placeholder for its real content. Idempotent -- safe to call on every
    // successful load, not just the first, so no extra "have we loaded before" state is needed anywhere.
    public void revealLoadedContent() {
        usersSplitPane.setVisible(true);
        usersSplitPane.setManaged(true);
        usersNoFileLabel.setVisible(false);
        usersNoFileLabel.setManaged(false);
    }

    // Re-reads the full user list from the engine and refreshes the list view; called right after a successful
    // load, and whenever any action anywhere reports that user data changed (via the coordinator).
    public void refreshUsersList() {
        try {
            List<UserSummaryDto> users = engine.listUsers();
            usersListView.getItems().setAll(users);
        } catch (GuessMarketException e) {
            // Not expected to be reachable right after a successful load, but handled defensively rather than assumed away.
            Dialogs.showError("Could not list users", e);
        }
    }

    // Looks up one user's full detail view and renders it in the right-hand details panel; called whenever the Users list selection changes.
    private void showUserDetails(String username) {
        try {
            UserDetailDto detail = engine.getUser(username);
            renderUserDetails(detail, null);
        } catch (GuessMarketException e) {
            Dialogs.showError("Could not load user details", e);
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
            Dialogs.showError("Could not load user details", e);
        }
    }

    // Rebuilds the details panel from scratch: the account-balance badge, the balance-history chart, the
    // events-participation list, and a per-event sub-panel (details + action controls) driven by whichever
    // participation gets selected. If eventIdToReselect is non-null, that participation is re-selected
    // programmatically after rebuilding the list.
    private void renderUserDetails(UserDetailDto detail, Integer eventIdToReselect) {
        Label balanceLabel = Labels.wrapping("Balance: " + Formatters.dollars(detail.balance())
                + (detail.blocked() ? "  (BLOCKED)" : ""));
        balanceLabel.getStyleClass().add("balance-badge");
        HBox balanceBadge = new HBox(balanceLabel);
        balanceBadge.setAlignment(Pos.CENTER_RIGHT);

        VBox singleEventDetailsBox = new VBox(10, new Label("Select an event above to view details"));

        ListView<UserEventParticipationDto> participationListView = new ListView<>();
        participationListView.getItems().setAll(detail.activeParticipations());
        participationListView.setPrefHeight(PARTICIPATION_LIST_HEIGHT);
        participationListView.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(UserEventParticipationDto participation, boolean empty) {
                super.updateItem(participation, empty);
                setText(empty || participation == null ? null : Formatters.participation(participation));
            }
        });
        participationListView.getSelectionModel().selectedItemProperty().addListener((observable, oldSelection, newSelection) -> {
            if (newSelection != null) {
                showUserEventDetails(newSelection.eventId(), singleEventDetailsBox, detail.username());
            }
        });

        userDetailsBox.getChildren().setAll(
                balanceBadge,
                BalanceHistoryChartBuilder.build(detail),
                Labels.sectionHeader("Events Participation / Owner:"),
                participationListView,
                new Separator(),
                Labels.sectionHeader("Single event details and trade:"),
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

    // Looks up one event's full status and renders it (details + action controls, pre-bound to username) in the given
    // container; called whenever the events-participation list selection changes.
    private void showUserEventDetails(int eventId, VBox container, String username) {
        try {
            EventStatusDto status = engine.getEventStatus(eventId);
            container.getChildren().clear();
            EventStatusPanelBuilder.append(container, status);
            container.getChildren().add(new Separator());
            // Same status gating as the Events tab: never show a control that can only fail.
            container.getChildren().add(EventActionsPanelBuilder.build(engine, coordinator, status, username,
                    newStatus -> refreshUserDetailsAfterPurchase(username, eventId)));
        } catch (GuessMarketException e) {
            Dialogs.showError("Could not load event details", e);
        }
    }
}
