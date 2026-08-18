package engine.impl.state;

import java.io.Serializable;
import java.util.List;

import engine.domain.Event;

// Serializable container for a full save/load-state snapshot: every event, in insertion order.
// Kept as a thin wrapper (rather than serializing EngineImpl's live Map directly) so the persistence
// format is not hard-wired to EngineImpl's internal representation.
final class EngineStateSnapshot implements Serializable {

    private static final long serialVersionUID = 1L;

    private final List<Event> events;

    EngineStateSnapshot(List<Event> events) {
        this.events = events;
    }

    List<Event> getEvents() {
        return events;
    }
}
