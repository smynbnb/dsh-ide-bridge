package dsh.web.plugin;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Watches the bridge file and reports state transitions to the tool window.
 *
 * <p>Polling rather than {@code WatchService}: on Windows a writer that
 * truncates and rewrites the file can produce no MODIFY event at all, and a
 * two-second stat is free compared with the cost of a silent stale page.</p>
 *
 * <p>The readiness probe performs an HTTP request, so it only runs when the
 * file actually changed (or on the first tick). Steady state costs one stat.</p>
 */
public final class BridgeWatcher implements Disposable {

    private static final Logger LOG = Logger.getInstance(BridgeWatcher.class);

    /** Poll cadence. Two seconds keeps restarts feeling instant without noise. */
    private static final long INTERVAL_SECONDS = 2L;

    private final Consumer<BridgeConfig.Result> listener;
    private final ScheduledExecutorService scheduler;

    private volatile boolean first = true;
    private volatile boolean lastExists;
    private volatile long lastSize = Long.MIN_VALUE;
    private volatile long lastModified = Long.MIN_VALUE;

    public BridgeWatcher(Consumer<BridgeConfig.Result> listener) {
        this.listener = listener;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "dsh-bridge-watcher");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Starts the poll loop immediately; the first result lands within one tick. */
    public void start() {
        scheduler.scheduleWithFixedDelay(this::tick, 0L, INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    private void tick() {
        try {
            boolean exists = false;
            long size = -1L;
            long modified = -1L;
            try {
                Path path = BridgeConfig.file();
                if (Files.isRegularFile(path)) {
                    exists = true;
                    size = Files.size(path);
                    modified = Files.getLastModifiedTime(path).toMillis();
                }
            } catch (IOException e) {
                LOG.debug("bridge file stat failed", e);
            }

            boolean changed = first
                    || exists != lastExists
                    || size != lastSize
                    || modified != lastModified;
            if (!changed) {
                return;
            }
            first = false;
            lastExists = exists;
            lastSize = size;
            lastModified = modified;

            BridgeConfig.Result result = BridgeConfig.probe();
            deliver(result);
        } catch (Throwable t) {
            LOG.warn("bridge watcher tick failed", t);
        }
    }

    private void deliver(BridgeConfig.Result result) {
        Application application = ApplicationManager.getApplication();
        if (application == null || application.isDisposed()) {
            return;
        }
        application.invokeLater(() -> {
            if (!isDisposed) {
                listener.accept(result);
            }
        });
    }

    private volatile boolean isDisposed;

    @Override
    public void dispose() {
        isDisposed = true;
        scheduler.shutdownNow();
    }
}
