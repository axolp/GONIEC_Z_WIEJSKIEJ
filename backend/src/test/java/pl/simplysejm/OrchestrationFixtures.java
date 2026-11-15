package pl.simplysejm;

import java.time.*;
import java.util.*;
import java.util.concurrent.Executor;

final class OrchestrationFixtures {
    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-15T12:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        public Instant instant() { return now; }
        void advance(Duration duration) { now = now.plus(duration); }
    }
    static final class ManualExecutor implements Executor {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        public void execute(Runnable task) { tasks.add(task); }
        void runNext() { tasks.remove().run(); }
    }
}
