package engine.impl.state;

import java.util.Map;

import engine.domain.Event;
import engine.domain.User;

// The result of a full state load: every event and every user, keyed the same way EngineImpl already keys its live maps. Internal to engine.impl.state — never crosses the IEngine boundary.
public record LoadedState(Map<Integer, Event> events, Map<String, User> users) {
}
