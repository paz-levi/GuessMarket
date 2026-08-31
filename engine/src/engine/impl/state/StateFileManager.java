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
import engine.domain.User;
import exception.StateFileException;

// Serializes/deserializes the full engine state to/from a save-state file; never touches EngineImpl's live state itself.
public final class StateFileManager {

    private static final String STATE_FILE_EXTENSION = ".gmstate";

    private StateFileManager() {
    }

    // Writes every event and user (in insertion order) to <filePath>.gmstate as a single object graph, preserving reference identity.
    public static void save(Map<Integer, Event> events, Map<String, User> users, String filePath) {
        File file = resolveFile(filePath);
        EngineStateSnapshot snapshot = new EngineStateSnapshot(new ArrayList<>(events.values()), new ArrayList<>(users.values()));
        // fileOut is its own resource (rather than inlined into the ObjectOutputStream constructor call) so it is still
        // closed if the ObjectOutputStream constructor itself throws.
        try (FileOutputStream fileOut = new FileOutputStream(file);
             ObjectOutputStream out = new ObjectOutputStream(fileOut)) {
            out.writeObject(snapshot);
        } catch (IOException e) {
            throw new StateFileException("The state could not be saved to \"" + file.getPath() + "\": " + e.getMessage());
        }
    }

    // Reads a previously saved state file and rebuilds fresh id -> event and name -> user maps; throws before any caller state is touched.
    public static LoadedState load(String filePath) {
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
            // A .gmstate file saved before users existed in the snapshot has no users in its stream at all, so
            // snapshot.getUsers() comes back null on an old file rather than an empty list -- guarded here.
            List<User> users = snapshot.getUsers() != null ? snapshot.getUsers() : List.of();
            return new LoadedState(toEventMap(snapshot.getEvents()), toUserMap(users));
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

    // Rebuilds the name -> user map from a flat user list, in insertion order.
    private static Map<String, User> toUserMap(List<User> users) {
        Map<String, User> userMap = new LinkedHashMap<>();
        for (User user : users) {
            userMap.put(user.getName(), user);
        }
        return userMap;
    }
}
