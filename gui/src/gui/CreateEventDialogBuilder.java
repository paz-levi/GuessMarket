package gui;

import java.util.function.Consumer;

import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.StringConverter;

import dto.CommissionMode;
import dto.CreateEventRequestDto;
import dto.EventStatusDto;
import dto.TradingMethod;
import exception.GuessMarketException;

// Builds and shows the "Create Event" modal dialog, invoked from the Events tab's toolbar. A plain static-method
// helper class, not FXML or a separate Controller, mirroring OrderBookPanelBuilder's exact role/reasoning per
// CLAUDE.md's recorded <fx:include>-deferral decision -- this keeps MainViewController from growing further
// without committing to a full inter-controller split yet.
final class CreateEventDialogBuilder {

    // Below this, the caption fires -- "Consider a larger value (e.g. 50+)" is the threshold, not a validation
    // rule: whether a small b actually causes a $0.00-priced trade depends on future trading volume the creator
    // can't know at creation time, so this is purely informational (never blocks Create), per CLAUDE.md's own
    // standing principle of not adding a restriction the spec doesn't require.
    private static final int LOW_LIQUIDITY_PARAMETER_THRESHOLD = 50;

    // Verified, not just derived from the general theory: e^100 completely absorbs e^14 in double precision (a
    // real hand-traced b=5 test event produced two genuinely-$0.00 trades, matching LmsrMath's own formula exactly
    // under double-precision arithmetic -- see CLAUDE.md's Update Log). double has ~15-17 significant decimal
    // digits, and e^x needs an exponent gap of roughly ln(10^16) =~ 37 before one term in the LMSR sum completely
    // swamps the other -- so once one option's shares/b outpaces the other's by roughly that much, the cheaper
    // option's trades can start pricing at exactly $0.00.
    private static final String LOW_LIQUIDITY_PARAMETER_WARNING =
            "Note: a very small b makes prices move sharply with even modest trading volume -- once "
            + "purchases substantially exceed b, the losing option's trades may price at exactly $0.00 "
            + "due to floating-point precision limits. Consider a larger value (e.g. 50+) unless you "
            + "specifically want an extremely sensitive market.";

    private CreateEventDialogBuilder() {
    }

    // Shows the dialog. On a successful creation, refreshes the Events list and hands the new event's status to
    // onCreated so the caller can display it immediately -- matching every other trading action's own
    // refresh-then-redraw pattern. Validation failures (either a NumberFormatException or a GuessMarketException
    // from the engine) keep the dialog open with the user's input intact, via the Create button's event filter below.
    static void show(MainViewController controller, Consumer<EventStatusDto> onCreated) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Create Event");
        // JavaFX Dialog defaults to non-resizable -- CLAUDE.md's resize rule applies to any window, not just the
        // primary Stage, so this must be set explicitly, the same as every other resizable window in this app.
        dialog.setResizable(true);
        dialog.setHeaderText("Define a brand-new event. You'll open it for trading afterward, the same as a loaded one.");

        ButtonType createButtonType = new ButtonType("Create", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(createButtonType, ButtonType.CANCEL);

        TextField nameField = new TextField();
        TextArea descriptionArea = new TextArea();
        descriptionArea.setPrefRowCount(3);
        descriptionArea.setWrapText(true);
        TextField optionOneField = new TextField();
        TextField optionTwoField = new TextField();
        // Reused as-is -- already reads from engine.listUsers(), needs no changes for this new caller.
        ComboBox<String> marketMakerComboBox = controller.buildUsernameComboBox();

        TextField commissionRateField = new TextField();
        commissionRateField.setPromptText("0-90");

        ComboBox<CommissionMode> commissionModeComboBox = new ComboBox<>();
        commissionModeComboBox.getItems().addAll(CommissionMode.ON_PURCHASE, CommissionMode.ON_CLOSE);
        commissionModeComboBox.setConverter(enumConverter(MainViewController::formatCommissionMode));
        commissionModeComboBox.getSelectionModel().selectFirst();

        ComboBox<TradingMethod> tradingMethodComboBox = new ComboBox<>();
        tradingMethodComboBox.getItems().addAll(TradingMethod.LMSR, TradingMethod.ORDER_BOOK);
        tradingMethodComboBox.setConverter(enumConverter(MainViewController::formatTradingMethod));
        tradingMethodComboBox.getSelectionModel().selectFirst();

        // LMSR's one field.
        TextField liquidityParameterField = new TextField();
        liquidityParameterField.setPromptText("positive integer");
        // Soft warning only -- shown live as the creator types, hidden by default (including for an empty/invalid
        // field) since there's nothing yet to warn about. setManaged(false) alongside setVisible(false) so the
        // hidden caption doesn't reserve blank space in the layout.
        Label lowLiquidityCaption = MainViewController.wrappingLabel(LOW_LIQUIDITY_PARAMETER_WARNING);
        lowLiquidityCaption.setVisible(false);
        lowLiquidityCaption.setManaged(false);
        liquidityParameterField.textProperty().addListener((observable, oldText, newText) -> {
            boolean showWarning = isLowLiquidityParameter(newText);
            lowLiquidityCaption.setVisible(showWarning);
            lowLiquidityCaption.setManaged(showWarning);
        });
        VBox lmsrFields = new VBox(6, MainViewController.wrappingLabel("Liquidity parameter (b):"),
                liquidityParameterField, lowLiquidityCaption);

        // Order Book's three fields.
        TextField initialField = new TextField();
        initialField.setPromptText(">= 0");
        TextField dField = new TextField();
        dField.setPromptText("positive integer");
        CheckBox allowMintCheckBox = new CheckBox("Allow mint");
        VBox orderBookFields = new VBox(6,
                MainViewController.wrappingLabel("Initial share stock:"), initialField,
                MainViewController.wrappingLabel("d (price ceiling basis):"), dField,
                allowMintCheckBox);

        // The concrete dynamic-visibility mechanism: swap the container's children wholesale on toggle, the same
        // imperative-rebuild style MainViewController.appendEventStatusDisplay already uses elsewhere.
        VBox methodSpecificFields = new VBox(6, lmsrFields);
        tradingMethodComboBox.getSelectionModel().selectedItemProperty().addListener((observable, oldMethod, newMethod) ->
                methodSpecificFields.getChildren().setAll(newMethod == TradingMethod.LMSR ? lmsrFields : orderBookFields));

        GridPane form = new GridPane();
        form.setHgap(8);
        form.setVgap(8);
        form.setPadding(new Insets(10));
        // Without this, wrappingLabel's own wrapping (correct in isolation) has nothing stopping the label column
        // itself from being squeezed arbitrarily thin as the dialog shrinks -- that's what produced the one-
        // character-per-line regression. minWidth 140 comfortably fits "Commission Rate (%):", the longest label
        // in this column, on one line at any dialog width down to the floor set below. The field column gets the
        // grow priority instead, so resizing changes the input fields' width, not the label column's.
        ColumnConstraints labelColumn = new ColumnConstraints();
        labelColumn.setMinWidth(140);
        ColumnConstraints fieldColumn = new ColumnConstraints();
        fieldColumn.setHgrow(Priority.ALWAYS);
        form.getColumnConstraints().addAll(labelColumn, fieldColumn);
        int row = 0;
        form.addRow(row++, MainViewController.wrappingLabel("Name:"), nameField);
        form.addRow(row++, MainViewController.wrappingLabel("Description:"), descriptionArea);
        form.addRow(row++, MainViewController.wrappingLabel("Option One Name:"), optionOneField);
        form.addRow(row++, MainViewController.wrappingLabel("Option Two Name:"), optionTwoField);
        form.addRow(row++, MainViewController.wrappingLabel("Market Maker:"), marketMakerComboBox);
        form.addRow(row++, MainViewController.wrappingLabel("Commission Rate (%):"), commissionRateField);
        form.addRow(row++, MainViewController.wrappingLabel("Commission Mode:"), commissionModeComboBox);
        form.addRow(row++, MainViewController.wrappingLabel("Trading Method:"), tradingMethodComboBox);
        form.add(methodSpecificFields, 0, row, 2, 1);

        dialog.getDialogPane().setContent(form);
        dialog.getDialogPane().setPrefWidth(420);
        // A sane practical floor only, not a spec number -- mirrors GuessMarketApp's own setMinWidth/setMinHeight
        // reasoning on the primary Stage. Without this, dialog.setResizable(true) alone lets the whole dialog be
        // dragged smaller than the label column's own minWidth (140) plus a usable field width can ever fit,
        // which would just move the "nowhere left to go" squeeze from the labels onto the fields/button bar
        // instead of actually fixing it. minWidth matches the existing prefWidth (already sized to comfortably fit
        // the 140px label column plus a real field width); minHeight is sized to the taller of the two
        // method-specific field groups (Order Book's three fields + checkbox) so toggling never clips either one.
        // DialogPane.setMinWidth/setMinHeight alone is NOT enough -- confirmed by a real resize harness, not
        // assumed: it only sets a layout preference on the DialogPane Region itself, not an enforced floor on the
        // actual OS-level Window, which a verification harness caught reporting getMinWidth()/getMinHeight() as
        // 0.0 even after the two calls below. The Window doesn't exist until the dialog is actually shown, so the
        // real floor has to be applied in setOnShown, mirroring the standard JavaFX pattern for sizing a Dialog's
        // underlying Stage.
        dialog.getDialogPane().setMinWidth(420);
        dialog.getDialogPane().setMinHeight(480);
        dialog.setOnShown(shownEvent -> Platform.runLater(() -> {
            // Deferred one further pulse past setOnShown itself -- confirmed necessary, not just cautious: at the
            // instant setOnShown fires, the Scene's width/height are not yet resolved (still their unset NaN
            // default) on this render pipeline, which silently poisoned the chrome computation below to NaN, and
            // Stage.setMinWidth/setMinHeight(NaN) is silently a no-op (every comparison against NaN is false) --
            // caught only because a resize harness's own "confirm shrinking below the floor is actually clamped"
            // check false-passed too, for the exact same NaN reason, until it was hardened to check for that.
            Stage dialogStage = (Stage) dialog.getDialogPane().getScene().getWindow();
            // Stage width/height include the OS window chrome (title bar, borders); the 420/480 above are
            // DialogPane content-area sizes, not Stage sizes -- applying the same raw numbers to both would let
            // the actual content area shrink below the DialogPane's own declared minimum by exactly the chrome's
            // size. Confirmed a real bug, not a theoretical one: a resize harness caught the DialogPane itself
            // clipping past the scene bounds (its required 480 height inside an actual 442.7-tall scene) when
            // this measured-overhead step was missing. Measured at runtime rather than a hardcoded guess, since
            // chrome size varies by OS/theme/DPI.
            double chromeWidth = dialogStage.getWidth() - dialogStage.getScene().getWidth();
            double chromeHeight = dialogStage.getHeight() - dialogStage.getScene().getHeight();
            dialogStage.setMinWidth(420 + chromeWidth);
            dialogStage.setMinHeight(480 + chromeHeight);
        }));

        // Kept open across a validation failure (unlike a plain Alert-then-close flow) so the user's already-typed
        // fields survive a retry -- an event filter on the Create button, not the dialog's own result handling, is
        // what makes that possible: consuming the ActionEvent stops the dialog from closing.
        dialog.getDialogPane().lookupButton(createButtonType).addEventFilter(ActionEvent.ACTION, actionEvent -> {
            CreateEventRequestDto request = tryBuildRequest(controller, nameField, descriptionArea, optionOneField,
                    optionTwoField, marketMakerComboBox, commissionRateField, commissionModeComboBox,
                    tradingMethodComboBox, liquidityParameterField, initialField, dField, allowMintCheckBox);
            if (request == null) {
                actionEvent.consume();
                return;
            }
            EventStatusDto created = tryCreateEvent(controller, request);
            if (created == null) {
                actionEvent.consume();
                return;
            }
            controller.refreshEventsList();
            onCreated.accept(created);
        });

        dialog.showAndWait();
    }

    // Whether text parses as a positive integer below the low-liquidity threshold -- garbage/blank/non-positive
    // input simply hides the caption (nothing to warn about yet), the same "hide on parse failure" spirit as every
    // other soft-feedback element in this dialog.
    private static boolean isLowLiquidityParameter(String text) {
        try {
            int value = Integer.parseInt(text.trim());
            return value >= 1 && value < LOW_LIQUIDITY_PARAMETER_THRESHOLD;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    // Parses/checks only what the GUI itself must before even calling the engine ("does this parse as a number",
    // "is a market maker selected") -- every real business rule (commission range, b/d/initial bounds, blank
    // names) is enforced server-side by IEngine.createEvent and deliberately not duplicated here, the same
    // convention OrderBookPanelBuilder.handleSubmitOrderClick already uses. Returns null (after showing the
    // specific error) on any failure.
    private static CreateEventRequestDto tryBuildRequest(MainViewController controller, TextField nameField,
            TextArea descriptionArea, TextField optionOneField, TextField optionTwoField,
            ComboBox<String> marketMakerComboBox, TextField commissionRateField,
            ComboBox<CommissionMode> commissionModeComboBox, ComboBox<TradingMethod> tradingMethodComboBox,
            TextField liquidityParameterField, TextField initialField, TextField dField, CheckBox allowMintCheckBox) {
        String marketMakerUsername = marketMakerComboBox.getSelectionModel().getSelectedItem();
        if (marketMakerUsername == null || marketMakerUsername.isBlank()) {
            controller.showErrorAlert("Invalid input", "Select a market maker.");
            return null;
        }

        TradingMethod tradingMethod = tradingMethodComboBox.getSelectionModel().getSelectedItem();
        int commissionRate;
        int liquidityParameter = 0;
        int initial = 0;
        int d = 0;
        try {
            commissionRate = Integer.parseInt(commissionRateField.getText().trim());
            if (tradingMethod == TradingMethod.LMSR) {
                liquidityParameter = Integer.parseInt(liquidityParameterField.getText().trim());
            } else {
                initial = Integer.parseInt(initialField.getText().trim());
                d = Integer.parseInt(dField.getText().trim());
            }
        } catch (NumberFormatException e) {
            controller.showErrorAlert("Invalid input", "Commission rate and the method-specific fields must be whole numbers.");
            return null;
        }

        return new CreateEventRequestDto(nameField.getText(), descriptionArea.getText(), optionOneField.getText(),
                optionTwoField.getText(), marketMakerUsername, commissionRate,
                commissionModeComboBox.getSelectionModel().getSelectedItem(), tradingMethod,
                liquidityParameter, initial, d, allowMintCheckBox.isSelected());
    }

    // Calls the existing IEngine.createEvent; returns null (after showing the real business-rule error) on any
    // failure, so the caller knows to keep the dialog open rather than treat this as success.
    private static EventStatusDto tryCreateEvent(MainViewController controller, CreateEventRequestDto request) {
        try {
            return controller.engine.createEvent(request);
        } catch (GuessMarketException e) {
            controller.showErrorAlert("Could not create event", e);
            return null;
        }
    }

    // A ComboBox StringConverter over one of MainViewController's existing humanized-enum formatters -- the same
    // "raw enum names aren't something an end user should see" convention already applied to the filter ComboBoxes.
    private static <T> StringConverter<T> enumConverter(java.util.function.Function<T, String> toLabel) {
        return new StringConverter<>() {
            @Override
            public String toString(T value) {
                return value == null ? "" : toLabel.apply(value);
            }

            @Override
            public T fromString(String string) {
                throw new UnsupportedOperationException(); // ComboBox selections are never parsed back from text
            }
        };
    }
}
