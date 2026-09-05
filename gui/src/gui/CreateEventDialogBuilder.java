package gui;

import java.util.function.Consumer;

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
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
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

    private CreateEventDialogBuilder() {
    }

    // Shows the dialog. On a successful creation, refreshes the Events list and hands the new event's status to
    // onCreated so the caller can display it immediately -- matching every other trading action's own
    // refresh-then-redraw pattern. Validation failures (either a NumberFormatException or a GuessMarketException
    // from the engine) keep the dialog open with the user's input intact, via the Create button's event filter below.
    static void show(MainViewController controller, Consumer<EventStatusDto> onCreated) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Create Event");
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
        VBox lmsrFields = new VBox(6, new Label("Liquidity parameter (b):"), liquidityParameterField);

        // Order Book's three fields.
        TextField initialField = new TextField();
        initialField.setPromptText(">= 0");
        TextField dField = new TextField();
        dField.setPromptText("positive integer");
        CheckBox allowMintCheckBox = new CheckBox("Allow mint");
        VBox orderBookFields = new VBox(6,
                new Label("Initial share stock:"), initialField,
                new Label("d (price ceiling basis):"), dField,
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
        int row = 0;
        form.addRow(row++, new Label("Name:"), nameField);
        form.addRow(row++, new Label("Description:"), descriptionArea);
        form.addRow(row++, new Label("Option One Name:"), optionOneField);
        form.addRow(row++, new Label("Option Two Name:"), optionTwoField);
        form.addRow(row++, new Label("Market Maker:"), marketMakerComboBox);
        form.addRow(row++, new Label("Commission Rate (%):"), commissionRateField);
        form.addRow(row++, new Label("Commission Mode:"), commissionModeComboBox);
        form.addRow(row++, new Label("Trading Method:"), tradingMethodComboBox);
        form.add(methodSpecificFields, 0, row, 2, 1);

        dialog.getDialogPane().setContent(form);
        dialog.getDialogPane().setPrefWidth(420);

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
