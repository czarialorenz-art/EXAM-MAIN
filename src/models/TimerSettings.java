package models;

import exceptions.InvalidExamException;
import util.TimeFormatter;

import java.io.Serializable;
import java.time.Duration;

/** Immutable, teacher-configured timer options for an exam. */
public final class TimerSettings implements Serializable {
    private static final long serialVersionUID = 1L;
    public static final Duration MIN_DURATION = Duration.ofMinutes(1);
    public static final Duration MAX_DURATION = Duration.ofHours(24);

    private final boolean enabled;
    private final Duration duration;
    private final boolean autoSubmit;
    private final boolean allowPause;

    public TimerSettings(boolean enabled, Duration duration, boolean autoSubmit, boolean allowPause)
            throws InvalidExamException {
        this.enabled = enabled;
        this.duration = duration;
        this.autoSubmit = autoSubmit;
        this.allowPause = enabled && allowPause;
        validate();
    }

    private TimerSettings(Duration duration) {
        this.enabled = true;
        this.duration = duration;
        this.autoSubmit = true;
        this.allowPause = false;
    }

    /** 30 minutes, automatic submission, no pausing. */
    public static TimerSettings defaults() {
        return new TimerSettings(Duration.ofMinutes(30));
    }

    public void validate() throws InvalidExamException {
        if (!enabled) {
            return;
        }
        if (duration == null) {
            throw new InvalidExamException("A duration is required when the timer is enabled.");
        }
        if (duration.compareTo(MIN_DURATION) < 0 || duration.compareTo(MAX_DURATION) > 0) {
            throw new InvalidExamException("Invalid examination duration (" + TimeFormatter.human(duration)
                    + "). It must be between 1 minute and 24 hours.");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Null when the timer is disabled. */
    public Duration getDuration() {
        return enabled ? duration : null;
    }

    public boolean isAutoSubmit() {
        return autoSubmit;
    }

    public boolean isAllowPause() {
        return allowPause;
    }

    public String describe() {
        String base = enabled ? TimeFormatter.human(duration) : "No time limit";
        return base + (autoSubmit ? ", auto-submit ON" : ", auto-submit OFF") + (allowPause ? ", pausing allowed" : "");
    }
}
