package engine.impl.state;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import engine.domain.Event;
import exception.StateFileException;

// Serializes/deserializes the full engine state to/from a save-state file; never touches EngineImpl's live state itself.
public final class StateFileManager {

    private static final String STATE_FILE_EXTENSION = ".gmstate";

    private StateFileManager() {
    }

    // Writes every event (in insertion order) to <filePath>.gmstate as a single object graph, preserving reference identity.
    public static void save(Map<Integer, Event> events, String filePath) {
        File file = resolveFile(filePath);
        EngineStateSnapshot snapshot = new EngineStateSnapshot(new ArrayList<>(events.values()));
        // fileOut is its own resource (rather than inlined into the ObjectOutputStream constructor call) so it is still
        // closed if the ObjectOutputStream constructor itself throws.
        try (FileOutputStream fileOut = new FileOutputStream(file);
             ObjectOutputStream out = new ObjectOutputStream(fileOut)) {
            out.writeObject(snapshot);
        } catch (IOException e) {
            throw new StateFileException("The state could not be saved to \"" + file.getPath() + "\": " + e.getMessage());
        }
    }

    // Reads a previously saved state file and rebuilds a fresh id -> event map; throws before any caller state is touched.
    public static Map<Integer, Event> load(String filePath) {
        File file = resolveFile(filePath);
        if (!file.isFile()) {
            throw new StateFileException("No saved state file was found at: \"" + file.getPath() + "\"");
        }
        // fileIn is its own resource (rather than inlined into the ObjectInputStream constructor call) so it is still
        // closed -- and the file unlocked -- if the ObjectInputStream constructor itself throws on a corrupt header.
        try (FileInputStream fileIn = new FileInputStream(file);
             ObjectInputStream in = new ObjectInputStream(fileIn)) {
            Object raw = in.readObject();
            if (!(raw instanceof EngineStateSnapshot snapshot)) {
                throw new StateFileException("The file does not contain a valid Guess Market saved state: \"" + file.getPath() + "\"");
            }
            return toEventMap(snapshot.getEvents());
        } catch (IOException | ClassNotFoundException e) {
            throw new StateFileException("The file could not be read as a valid saved state: \"" + file.getPath() + "\" (" + e.getMessage() + ")");
        }
    }

    // Checks the path is non-blank and resolves it against the fixed save-state extension.
    private static File resolveFile(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            throw new StateFileException("The file path must not be empty.");
        }
        return new File(filePath + STATE_FILE_EXTENSION);
    }

    // Rebuilds the id -> event map from a flat event list, in insertion order.
    private static Map<Integer, Event> toEventMap(List<Event> events) {
        Map<Integer, Event> eventMap = new LinkedHashMap<>();
        for (Event event : events) {
            eventMap.put(event.getId(), event);
        }
        return eventMap;
    }
}
