package gui.tabs;

import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Separator;
import javafx.scene.control.SplitPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import dto.UserDetailDto;
import dto.UserEventParticipationDto;
import dto.UserSummaryDto;
import engine.IEngine;
import gui.common.Async;
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

    // Null under the plain in-process launch (no login screen) -- see showUserEventDetails's own doc for what that
    // means for the per-event action controls rendered below.
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
    // load, and whenever any action anywhere reports that user data changed (via the coordinator). Every IEngine
    // call is a real network round-trip once this is backed by HttpEngineClient, so it runs on a background Task --
    // see gui.common.Async.
    public void refreshUsersList() {
        Async.run(engine::listUsers,
                users -> usersListView.getItems().setAll(users),
                failure -> Dialogs.showError("Could not list users", failure));
    }

    // Looks up one user's full detail view and renders it in the right-hand details panel; called whenever the
    // Users list selection changes. Runs on a background Task -- see refreshUsersList's own note above.
    private void showUserDetails(String selectedUsername) {
        Async.run(() -> engine.getUser(selectedUsername),
                detail -> renderUserDetails(detail, null),
                failure -> Dialogs.showError("Could not load user details", failure));
    }

    // Re-fetches viewedUsername's full detail view after a purchase made from their own tab, then rebuilds all three
    // sections fresh (the balance badge and that event's participation entry both changed, not just the sub-panel
    // being viewed), re-selecting eventNameToReselect afterward so the user doesn't lose their place. Runs on a
    // background Task -- see refreshUsersList's own note above.
    private void refreshUserDetailsAfterPurchase(String viewedUsername, String eventNameToReselect) {
        Async.run(() -> engine.getUser(viewedUsername),
                detail -> renderUserDetails(detail, eventNameToReselect),
                failure -> Dialogs.showError("Could not load user details", failure));
    }

    // Rebuilds the details panel from scratch: the account-balance badge, the balance-history chart, the
    // events-participation list, and a per-event sub-panel (details + action controls) driven by whichever
    // participation gets selected. If eventNameToReselect is non-null, that participation is re-selected
    // programmatically after rebuilding the list.
    private void renderUserDetails(UserDetailDto detail, String eventNameToReselect) {
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
                showUserEventDetails(newSelection.eventName(), singleEventDetailsBox, detail.username());
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

        if (eventNameToReselect != null) {
            for (UserEventParticipationDto participation : participationListView.getItems()) {
                if (participation.eventName().equals(eventNameToReselect)) {
                    participationListView.getSelectionModel().select(participation);
                    break;
                }
            }
        }
    }

    // Looks up one event's full status and renders it (details + action controls) in the given container; called
    // whenever the events-participation list selection changes. Runs on a background Task -- see refreshUsersList's
    // own note above. viewedUsername is whichever user's page this participation was selected on (Ex2's own
    // impersonation model: act as whoever's page you're viewing) -- but under Exercise 3's client, every action
    // always executes as the actual logged-in session user regardless of what username a request carries (the
    // server derives identity from the session, never from a field), so the action panel below is bound to the
    // real session username (this.username) whenever one exists, falling back to viewedUsername only under the
    // plain in-process launch, which has no session concept to prefer instead.
    private void showUserEventDetails(String eventName, VBox container, String viewedUsername) {
        Async.run(() -> engine.getEventStatus(eventName),
                status -> {
                    String actingUsername = username != null ? username : viewedUsername;
                    container.getChildren().clear();
                    EventStatusPanelBuilder.append(container, status);
                    container.getChildren().add(new Separator());
                    // Same status gating as the Events tab: never show a control that can only fail.
                    container.getChildren().add(EventActionsPanelBuilder.build(engine, coordinator, status, actingUsername,
                            newStatus -> refreshUserDetailsAfterPurchase(viewedUsername, eventName)));
                },
                failure -> Dialogs.showError("Could not load event details", failure));
    }
}
