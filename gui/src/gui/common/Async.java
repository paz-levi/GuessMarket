package gui.common;

import java.util.concurrent.Callable;
import java.util.function.Consumer;

import javafx.concurrent.Task;

// The one shared way every gui.* call site runs an IEngine call off the FX Application Thread. Every IEngine method
// becomes a network round-trip once it's backed by HttpEngineClient (Exercise 3), so none of them may be called
// synchronously from a click/selection handler any more -- that would freeze the whole UI for the duration of each
// request. A plain static utility, not a controller method, because callers are a mix of controller instances
// (EventsTabController, UsersTabController) and static builder methods with no controller reference at all
// (EventActionsPanelBuilder, OrderBookPanelBuilder). Mirrors the one Task shape already used by
// MainViewController.runLoad (setOnSucceeded/setOnFailed run back on the FX thread automatically -- no manual
// Platform.runLater needed at any call site).
public final class Async {

    private Async() {
    }

    // Runs work on a background daemon thread; onSuccess/onFailure are invoked back on the FX Application Thread.
    public static <T> void run(Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onFailure) {
        Task<T> task = new Task<>() {
            @Override
            protected T call() throws Exception {
                return work.call();
            }
        };
        task.setOnSucceeded(event -> onSuccess.accept(task.getValue()));
        task.setOnFailed(event -> onFailure.accept(task.getException()));

        Thread thread = new Thread(task, "guessmarket-async");
        thread.setDaemon(true);
        thread.start();
    }
}
