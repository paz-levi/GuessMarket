package gui.components;

import javafx.scene.control.ComboBox;

import dto.UserSummaryDto;
import engine.IEngine;
import exception.GuessMarketException;

// Builds a username picker populated from the engine's currently loaded users, for any form that has no
// already-selected user to bind to. Takes the engine directly rather than reaching into a controller, so any
// module's screens can build the same picker.
public final class UsernamePicker {

    private UsernamePicker() {
    }

    public static ComboBox<String> build(IEngine engine) {
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
}
