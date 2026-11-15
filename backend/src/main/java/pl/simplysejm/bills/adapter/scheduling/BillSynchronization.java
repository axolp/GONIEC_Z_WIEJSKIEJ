package pl.simplysejm.bills.adapter.scheduling;

import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;
import pl.simplysejm.bills.api.BillSync;
import pl.simplysejm.bootstrap.SejmProperties;

@Component
@ConditionalOnProperty(name = "sejm.scheduling-enabled", havingValue = "true", matchIfMissing = true)
@EnableScheduling
public class BillSynchronization {
    private final BillSync sync;
    private final SejmProperties properties;
    public BillSynchronization(BillSync sync, SejmProperties properties) { this.sync = sync; this.properties = properties; }
    @EventListener(ApplicationReadyEvent.class)
    public void startup() { if (properties.startupSync()) sync.requestSync(); }
    @Scheduled(fixedDelayString = "${sejm.interval-minutes:30}", initialDelayString = "${sejm.interval-minutes:30}", timeUnit = TimeUnit.MINUTES)
    public void poll() { sync.requestSync(); }
}
