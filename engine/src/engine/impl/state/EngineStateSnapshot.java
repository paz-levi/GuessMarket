package engine.impl.state;

import java.io.Serializable;
import java.util.List;

import engine.domain.Event;
import engine.domain.User;

// Serializable container for a full save/load-state snapshot: every event and every user, in insertion order.
// Kept as a thin wrapper (rather than serializing EngineImpl's live Maps directly) so the persistence
// format is not hard-wired to EngineImpl's internal representation.
final class EngineStateSnapshot implements Serializable {

    private static final long serialVersionUID = 1L;

    private final List<Event> events;
    private final List<User> users;

    EngineStateSnapshot(List<Event> events, List<User> users) {
        this.events = events;
        this.users = users;
    }

    List<Event> getEvents() {
        return events;
    }

    List<User> getUsers() {
        return users;
    }
}
