package gui.tabs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.IntFunction;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Separator;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import dto.LedgerDeltaDto;
import dto.TransactionRecordDto;
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

    // Ledger state for whichever render currently shows the logged-in user's OWN account (see viewingOwnAccount
    // below) -- persists across renders so pollLedger can append to the exact ObservableList the currently-visible
    // ListView (if any) is bound to, without needing a full re-render for every periodic tick. Kept in ASCENDING
    // sequence order deliberately (a considered UX choice, not just implementation convenience): that's the shape
    // delta batches already arrive in (LedgerDeltaDto.entries() is ascending), so appending a poll's results is a
    // trivial addAll with zero re-sort, and it mirrors a chat log's own "grows downward" feel -- matching
    // docs-reference/ex3-plan.md's explicit framing of the ledger as "append-only, like chat messages", a
    // deliberate departure from this app's other newest-first tables (trade history, participations).
    private final ObservableList<TransactionRecordDto> ledgerItems = FXCollections.observableArrayList();
    private int ledgerSinceCursor;

    // True only when the currently-rendered detail panel is the logged-in user's own -- the single source of truth
    // for two separate gates: whether the Deposit control is shown at all (see the design-correctness note this was
    // built around: the server always deposits into the SESSION user regardless of any username the client sends,
    // so a Deposit control on another user's page would silently deposit into your own account while appearing to
    // target theirs -- it must only ever be reachable here), and whether pollLedger's periodic tick does anything.
    private boolean viewingOwnAccount;

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
    // load, whenever any action anywhere reports that user data changed (via the coordinator), and on every
    // periodic poll tick (see client.ClientApp's Timer). Every IEngine call is a real network round-trip once this
    // is backed by HttpEngineClient, so it runs on a background Task -- see gui.common.Async.
    public void refreshUsersList() {
        Async.run(engine::listUsers,
                users -> usersListView.getItems().setAll(users),
                failure -> Dialogs.showError("Could not list users", failure));
    }

    // Looks up one user's full detail view and renders it in the right-hand details panel; called whenever the
    // Users list selection changes, and after a successful deposit (see handleDepositClick). Runs on a background
    // Task -- see refreshUsersList's own note above.
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
    // events-participation list, a per-event sub-panel (details + action controls) driven by whichever
    // participation gets selected, and -- only when this is the logged-in user's OWN account (viewingOwnAccount) --
    // the deposit form and the transaction ledger. If eventNameToReselect is non-null, that participation is
    // re-selected programmatically after rebuilding the list.
    private void renderUserDetails(UserDetailDto detail, String eventNameToReselect) {
        viewingOwnAccount = username != null && username.equals(detail.username());

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

        List<Node> children = new ArrayList<>(List.of(
                balanceBadge,
                BalanceHistoryChartBuilder.build(detail),
                Labels.sectionHeader("Events Participation / Owner:"),
                participationListView,
                new Separator(),
                Labels.sectionHeader("Single event details and trade:"),
                singleEventDetailsBox
        ));

        if (viewingOwnAccount) {
            seedLedger(detail.transactions());

            ListView<TransactionRecordDto> ledgerListView = new ListView<>(ledgerItems);
            ledgerListView.setPrefHeight(PARTICIPATION_LIST_HEIGHT);
            ledgerListView.setCellFactory(list -> new ListCell<>() {
                @Override
                protected void updateItem(TransactionRecordDto transaction, boolean empty) {
                    super.updateItem(transaction, empty);
                    setText(empty || transaction == null ? null : Formatters.transactionRow(transaction));
                }
            });

            children.add(new Separator());
            children.add(Labels.sectionHeader("Deposit Funds:"));
            children.add(buildDepositForm(detail.username()));
            children.add(new Separator());
            children.add(Labels.sectionHeader("Transaction Ledger:"));
            children.add(ledgerListView);
        }

        userDetailsBox.getChildren().setAll(children);

        if (eventNameToReselect != null) {
            for (UserEventParticipationDto participation : participationListView.getItems()) {
                if (participation.eventName().equals(eventNameToReselect)) {
                    participationListView.getSelectionModel().select(participation);
                    break;
                }
            }
        }
    }

    // Resets the ledger to exactly what this fresh detail snapshot carries -- called on every full render of the
    // own-account view, so the ledger never drifts across re-renders (a full render already re-fetches getUser, so
    // re-seeding from that same fresh transactions() list is free). transactions() is newest-first (its own
    // documented convention); reversed once here to seed the ascending list pollLedger's delta batches append to.
    private void seedLedger(List<TransactionRecordDto> newestFirst) {
        ledgerItems.clear();
        List<TransactionRecordDto> ascending = new ArrayList<>(newestFirst);
        Collections.reverse(ascending);
        ledgerItems.addAll(ascending);
        ledgerSinceCursor = ascending.isEmpty() ? 0 : ascending.get(ascending.size() - 1).sequence();
    }

    // Builds the deposit form: an amount TextField + button, bound to ownUsername (always the logged-in user's own
    // name here -- see the class-level viewingOwnAccount doc for why this is the only place a deposit control may
    // ever appear). Client-side validation mirrors every other form in this app: a genuine parse failure or a
    // non-positive amount is the only UI-level check, everything else (the server's own > 0 rule) is left to
    // IEngine.depositFunds to reject.
    private HBox buildDepositForm(String ownUsername) {
        TextField amountField = new TextField();
        amountField.setPromptText("Amount");
        amountField.setPrefColumnCount(6);

        Button depositButton = new Button("Deposit");
        depositButton.setOnAction(event -> handleDepositClick(ownUsername, amountField, depositButton));

        return new HBox(8, amountField, depositButton);
    }

    // Deposits via the existing IEngine.depositFunds, then re-renders this same own-account view (picking up the
    // new balance and the new DEPOSIT ledger line in one fetch) and refreshes the users list -- deliberately NOT
    // coordinator.refreshEvents() too, unlike every trading action's paired refresh, since a deposit changes no
    // event-side state. Runs on a background Task; the button is disabled for the call's duration to prevent a
    // double-submit, matching every other money-moving form in this app.
    private void handleDepositClick(String ownUsername, TextField amountField, Button depositButton) {
        double amount;
        try {
            amount = Double.parseDouble(amountField.getText().trim());
        } catch (NumberFormatException e) {
            Dialogs.showError("Invalid input", "Amount must be a number.");
            return;
        }
        if (amount <= 0) {
            Dialogs.showError("Invalid input", "Amount must be positive.");
            return;
        }

        depositButton.setDisable(true);
        double depositedAmount = amount;
        Async.run(() -> {
                    engine.depositFunds(ownUsername, depositedAmount);
                    return null;
                },
                v -> {
                    depositButton.setDisable(false);
                    Dialogs.showDepositConfirmation(depositedAmount);
                    showUserDetails(ownUsername);
                    coordinator.refreshUsers();
                },
                failure -> {
                    depositButton.setDisable(false);
                    Dialogs.showError("Could not deposit funds", failure);
                });
    }

    // Called once per periodic poll tick (see client.ClientApp's Timer) -- a no-op whenever the currently-rendered
    // account isn't the logged-in user's own (viewingOwnAccount), so no network call happens for a page nobody's
    // ledger-watching. fetchDelta is supplied by the caller (HttpEngineClient::getLedgerDelta in practice) rather
    // than called directly, so this class -- and the whole gui module -- never needs a dependency on client.http;
    // LedgerDeltaDto/TransactionRecordDto live in engine's own dto package, which gui already depends on. A single
    // missed poll tick is swallowed rather than popping an error dialog every second the network hiccups.
    public void pollLedger(IntFunction<LedgerDeltaDto> fetchDelta) {
        if (!viewingOwnAccount) {
            return;
        }
        Async.run(() -> fetchDelta.apply(ledgerSinceCursor),
                delta -> {
                    if (!delta.entries().isEmpty()) {
                        ledgerItems.addAll(delta.entries());
                        ledgerSinceCursor = delta.version();
                    }
                },
                failure -> { });
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
                    // showOpenControl=false: the Users tab never shows an Open control (Events-tab-only), independent
                    // of actingUsername -- see EventActionsPanelBuilder.build's own doc for why these are kept as
                    // two separate parameters rather than one collapsed into the other.
                    container.getChildren().add(EventActionsPanelBuilder.build(engine, coordinator, status, actingUsername, false,
                            newStatus -> refreshUserDetailsAfterPurchase(viewedUsername, eventName)));
                },
                failure -> Dialogs.showError("Could not load event details", failure));
    }
}
