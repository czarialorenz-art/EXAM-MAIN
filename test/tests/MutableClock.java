package tests;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/** A clock the tests can move forward, so timer behaviour is tested without waiting. */
public class MutableClock extends Clock {
    private Instant now;
    private final ZoneId zone;

    public MutableClock(Instant start, ZoneId zone) {
        this.now = start;
        this.zone = zone;
    }

    public synchronized void advance(Duration d) {
        now = now.plus(d);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new MutableClock(now, zone);
    }

    @Override
    public synchronized Instant instant() {
        return now;
    }
}
