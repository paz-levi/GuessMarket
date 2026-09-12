package gui.tabs;

import java.util.function.Function;
import java.util.function.IntFunction;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

import dto.ChatDeltaDto;
import dto.ChatMessageDto;
import gui.common.Async;
import gui.common.Dialogs;
import gui.common.Formatters;

// The Chat tab: a global, append-only message feed shared by every logged-in user -- entirely separate from the
// prediction-market product itself, hence its own top-level tab rather than a section nested in Users. Only
// meaningful under Exercise 3's HTTP client (chat is not an IEngine capability at all, see engine.chat.ChatManager's
// own doc), so this defaults to a "logged in required" placeholder and only reveals real content once setUsername
// receives a real username -- same reveal-gate shape as UsersTab's "No file loaded" placeholder, and the same
// username == null convention every other Ex3-only control in this app already uses.
//
// Decoupled from HTTP entirely, like every other gui.tabs controller: the actual send/poll calls are supplied by
// whoever hosts this tab (client.ClientApp, via MainViewController) as plain function references
// (HttpEngineClient::sendChatMessage / ::getChatDelta) -- mirrors UsersTabController.pollLedger's own
// IntFunction<LedgerDeltaDto> pattern exactly, so this class -- and the whole gui module -- never needs a
// dependency on client.http.
public class ChatTabController {

    @FXML
    private VBox chatContentBox;

    @FXML
    private Label chatUnavailableLabel;

    @FXML
    private ListView<ChatMessageDto> chatListView;

    @FXML
    private TextField messageField;

    @FXML
    private Button sendButton;

    // Ascending by arrival, same "grows downward like a chat log" convention the ledger already established
    // (docs-reference/ex3-plan.md's own framing) -- ChatDeltaDto.messages() already arrives in this order, from
    // both a poll and a send's own echo, so appending is always a plain addAll with no re-sort.
    private final ObservableList<ChatMessageDto> chatItems = FXCollections.observableArrayList();
    private int chatSinceCursor;

    // Null under the plain in-process launch (no login screen, no chat backend to talk to) -- see setUsername.
    private String username;

    // Supplied once by the hosting shell right after a real login (client.ClientApp, via
    // MainViewController.setChatSender) -- never set under the plain in-process launch, so handleSendClick can only
    // ever be reached once the real content (gated on username != null) is visible in the first place.
    private Function<String, ChatDeltaDto> sender;

    @FXML
    private void initialize() {
        chatListView.setItems(chatItems);
        chatListView.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(ChatMessageDto message, boolean empty) {
                super.updateItem(message, empty);
                setText(empty || message == null ? null : Formatters.chatMessage(message));
            }
        });
        sendButton.setOnAction(event -> handleSendClick());
        messageField.setOnAction(event -> handleSendClick()); // Enter in the field submits, same as clicking Send
    }

    // Set by the hosting shell once a real logged-in username exists (Exercise 3's ClientApp only) -- toggles
    // between the real chat content and the "logged in required" placeholder. Left null (placeholder showing) under
    // the plain in-process launch, which never calls this at all.
    public void setUsername(String username) {
        this.username = username;
        boolean available = username != null;
        chatContentBox.setVisible(available);
        chatContentBox.setManaged(available);
        chatUnavailableLabel.setVisible(!available);
        chatUnavailableLabel.setManaged(!available);
    }

    // Supplied once by the hosting shell right after login -- see the class-level doc for why this is a plain
    // function reference rather than a dependency on client.http.
    public void setSender(Function<String, ChatDeltaDto> sender) {
        this.sender = sender;
    }

    // Sends the typed message, then applies the resulting delta as the instant local echo -- not waiting for the
    // next poll tick. Runs on a background Task -- see gui.common.Async; the field/button are disabled for the
    // call's duration to prevent a double-submit, matching every other form in this app.
    private void handleSendClick() {
        String text = messageField.getText() == null ? "" : messageField.getText().trim();
        if (text.isEmpty()) {
            return;
        }
        messageField.setDisable(true);
        sendButton.setDisable(true);
        Async.run(() -> sender.apply(text),
                delta -> {
                    messageField.clear();
                    messageField.setDisable(false);
                    sendButton.setDisable(false);
                    applyDelta(delta);
                },
                failure -> {
                    messageField.setDisable(false);
                    sendButton.setDisable(false);
                    Dialogs.showError("Could not send message", failure);
                });
    }

    // Called once per periodic poll tick (see client.ClientApp's Timer) -- a no-op whenever nobody is logged in, so
    // no network call happens under the plain in-process launch. fetchDelta is supplied by the caller
    // (HttpEngineClient::getChatDelta in practice), same shape as UsersTabController.pollLedger. A single missed
    // poll tick is swallowed rather than popping an error dialog every second the network hiccups.
    public void pollChat(IntFunction<ChatDeltaDto> fetchDelta) {
        if (username == null) {
            return;
        }
        Async.run(() -> fetchDelta.apply(chatSinceCursor), this::applyDelta, failure -> { });
    }

    // Shared by both the instant echo (handleSendClick) and every periodic poll (pollChat): append whatever new
    // messages arrived and advance the cursor to the delta's own version -- the one code path that keeps a sender's
    // own message from ever being re-delivered by their next poll (see ChatManager.postMessage's own doc).
    private void applyDelta(ChatDeltaDto delta) {
        if (!delta.messages().isEmpty()) {
            chatItems.addAll(delta.messages());
        }
        chatSinceCursor = delta.version();
    }
}
