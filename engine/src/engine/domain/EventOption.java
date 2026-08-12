package engine.domain;

// One of an event's two GM-options (outcomes) that users can buy into.
public final class EventOption {

    private final String name;

    public EventOption(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }
}
