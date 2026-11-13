package ui;

import exceptions.QuizSystemException;
import models.ExamSession;
import models.SessionState;
import services.ExamSessionService;
import util.TimeFormatter;

import java.io.PrintStream;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Background countdown for the console exam screen. Once per second it:
 * <ul>
 *   <li>prints a one-time warning at 10, 5 and 1 minute(s) remaining;</li>
 *   <li>asks {@link ExamSessionService#autoSubmitIfDue} to finish the attempt once the stored
 *       deadline has passed (the service, not this thread, decides whether time is up).</li>
 * </ul>
 * The timer holds no time state of its own, so it cannot reset anything.
 */
public class ExamTimer {
    private static final long[] WARNINGS_SECONDS = {600, 300, 60};

    private final ExamSessionService sessions;
    private final ExamSession session;
    private final Clock clock;
    private final PrintStream out;
    private final Set<Long> warned = new HashSet<>();
    private ScheduledExecutorService scheduler;

    public ExamTimer(ExamSessionService sessions, ExamSession session, Clock clock, PrintStream out) {
        this.sessions = sessions;
        this.session = session;
        this.clock = clock;
        this.out = out;
        // Do not show warnings for thresholds that had already passed before this screen opened.
        Duration remaining = session.remaining(clock.instant());
        if (remaining != null) {
            for (long w : WARNINGS_SECONDS) {
                if (remaining.getSeconds() < w - 1) {
                    warned.add(w);
                }
            }
        }
    }

    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "exam-timer");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::tick, 1, 1, TimeUnit.SECONDS);
    }

    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    /** One timer step (public so tests can drive it with a fake clock). */
    public void tick() {
        try {
            if (!session.isOpen()) {
                stop();
                return;
            }
            if (session.getState() != SessionState.PAUSED) {
                Duration remaining = session.remaining(clock.instant());
                if (remaining != null) {
                    for (long w : WARNINGS_SECONDS) {
                        if (remaining.getSeconds() <= w && remaining.getSeconds() > 0 && warned.add(w)) {
                            out.println();
                            out.println("*** WARNING: " + (w / 60) + " minute(s) remaining (" + TimeFormatter.hms(remaining)
                                    + ") ***");
                        }
                    }
                }
            }
            if (sessions.autoSubmitIfDue(session.getSessionId())) {
                out.println();
                out.println("*** TIME IS UP *** " + sessions.describeEnding(session));
                out.println("Press Enter to continue.");
                stop();
            }
        } catch (QuizSystemException | RuntimeException e) {
            out.println();
            out.println("[ERROR] Timer problem: " + e.getMessage());
        }
    }

    public Set<Long> getWarningsShown() {
        return Set.copyOf(warned);
    }
}
