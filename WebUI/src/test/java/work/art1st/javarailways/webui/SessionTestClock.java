package work.art1st.javarailways.webui;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

final class SessionTestClock extends Clock {
    private volatile Instant instant = Instant.parse("2026-11-01T12:00:00Z");
    void advance(Duration duration) { instant = instant.plus(duration); }
    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant, zone); }
    @Override public Instant instant() { return instant; }
}
