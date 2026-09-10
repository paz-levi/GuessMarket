package gui.components;

import javafx.scene.control.ComboBox;

import engine.IEngine;
import gui.common.Async;

// Builds a username picker populated from the engine's currently loaded users, for any form that has no
// already-selected user to bind to -- only ever reached via the plain in-process launch's Events-tab fallback
// (Exercise 3's client always supplies a real logged-in username instead, see EventActionsPanelBuilder.build's own
// doc). Takes the engine directly rather than reaching into a controller, so any module's screens can build the
// same picker.
public final class UsernamePicker {

    private UsernamePicker() {
    }

    // Returns the (initially empty) ComboBox immediately and populates it once engine.listUsers() returns -- every
    // IEngine call runs on a background Task (gui.common.Async), never synchronously on the FX thread, exactly
    // like every other engine call site in this module, even though this one happens to be unreachable when engine
    // is HTTP-backed.
    public static ComboBox<String> build(IEngine engine) {
        ComboBox<String> comboBox = new ComboBox<>();
        Async.run(engine::listUsers,
                users -> {
                    users.forEach(user -> comboBox.getItems().add(user.username()));
                    comboBox.getSelectionModel().selectFirst();
                },
                failure -> {
                    // A file is definitely loaded here (we're already showing event details), so this shouldn't
                    // happen in practice -- leave the combo box empty rather than block the rest of the form from
                    // rendering.
                });
        return comboBox;
    }
}
