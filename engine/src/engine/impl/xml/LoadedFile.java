package engine.impl.xml;

import java.util.List;

import engine.domain.Event;
import engine.domain.User;

// The result of a full, validated file load: every event and every user, cross-referenced (each event's MM is already assigned). Internal to engine.impl.xml — never crosses the IEngine boundary.
public record LoadedFile(List<Event> events, List<User> users) {
}
