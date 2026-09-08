package gui.tabs;

// The contract between a shell screen and the tabs/components it hosts: a way to say "data I don't own just
// changed, refresh it" without any tab or panel ever referencing another one directly. Keeps the wiring a tree
// (shell -> tabs), never a cycle.
//
// Two methods rather than one refreshAll() because the real call sites genuinely differ: a filter change or a new
// event refreshes the events list alone, while any trade moves money and so refreshes both.
//
// Deliberately an interface, not the concrete shell controller: a different module (Exercise 3's client app) can
// host these same tabs and components under its own shell by implementing this, with no dependency on
// gui.MainViewController.
public interface TabCoordinator {

    // Re-reads the events list from the engine and repaints it.
    void refreshEvents();

    // Re-reads the users list from the engine and repaints it.
    void refreshUsers();
}
