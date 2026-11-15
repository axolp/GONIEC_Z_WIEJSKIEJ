package pl.simplysejm;

import java.io.IOException;
import java.time.*;
import java.util.*;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.*;
import pl.simplysejm.bills.api.BillQuery;
import pl.simplysejm.votings.api.BillVotings;
import pl.simplysejm.votings.application.VotingService;
import pl.simplysejm.votings.port.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static pl.simplysejm.VotingFixtures.*;

class VotingServiceTest {
    final VotingMappingSource mappings = mock(VotingMappingSource.class);
    final VotingRepository repository = mock(VotingRepository.class);
    final VotingSource source = mock(VotingSource.class);
    final BillQuery bills = mock(BillQuery.class);
    final OrchestrationFixtures.MutableClock clock = new OrchestrationFixtures.MutableClock();
    final OrchestrationFixtures.ManualExecutor executor = new OrchestrationFixtures.ManualExecutor();
    final VotingMappingSource.Mapping mapping = new VotingMappingSource.Mapping(snapshot(), "a".repeat(64));
    @BeforeEach void setup() throws Exception {
        when(mappings.read()).thenReturn(new VotingMappingSource.View(Map.of("10-2693", mapping), null));
        when(repository.read(anyString(), any())).thenReturn(Optional.empty()); when(source.fetch(any())).thenReturn(live());
    }
    VotingService service() { return new VotingService(mappings, repository, source, bills, 30, 60, clock, executor); }
    @Test void deduplicatesQueuedRefreshAndExpiresExactlyAtTtl() throws Exception {
        var service = service(); assertThat(service.get("10-2693").mode()).isEqualTo("snapshot");
        service.get("10-2693"); assertThat(executor.tasks).hasSize(1); verifyNoInteractions(source);
        executor.runNext(); assertThat(service.get("10-2693").stale()).isFalse();
        clock.advance(Duration.ofMinutes(30).minusNanos(1)); service.get("10-2693"); assertThat(executor.tasks).isEmpty();
        clock.advance(Duration.ofNanos(1)); assertThat(service.get("10-2693").stale()).isTrue();
        assertThat(executor.tasks).hasSize(1); executor.runNext(); verify(source, times(2)).fetch(any());
    }
    @Test void failureRetriesExactlyAtBoundaryAndNeverPersistsFabricatedData() throws Exception {
        when(source.fetch(any())).thenThrow(new IOException("offline")); var service = service();
        service.get("10-2693"); executor.runNext();
        assertThat(service.get("10-2693").error()).contains("offline");
        assertThat(service.get("10-2693").mode()).isEqualTo("snapshot");
        clock.advance(Duration.ofSeconds(60).minusNanos(1)); service.get("10-2693"); assertThat(executor.tasks).isEmpty();
        clock.advance(Duration.ofNanos(1)); service.get("10-2693"); assertThat(executor.tasks).hasSize(1);
        verify(repository, never()).save(any());
    }
    @Test void rejectedExecutionResetsFlagAndRetriesAfterDelay() {
        var rejected = new java.util.concurrent.atomic.AtomicInteger();
        var service = new VotingService(mappings, repository, source, bills, 30, 60, clock, task -> {
            rejected.incrementAndGet(); throw new RejectedExecutionException("full");
        });
        assertThat(service.get("10-2693").error()).contains("chwilowo"); service.get("10-2693");
        assertThat(rejected.get()).isEqualTo(1); clock.advance(Duration.ofSeconds(60)); service.get("10-2693");
        assertThat(rejected.get()).isEqualTo(2); verifyNoInteractions(source);
    }
    @Test void saveFailureKeepsPreviousLiveDataAndRetryUsesSameClock() throws Exception {
        var response = new BillVotings.Response("10-2693", clock.instant().toString(), snapshot().sourceUrl(), snapshot().defaultVotingId(), List.of(live()), "live", false, null);
        var cached = new VotingRepository.Cached(1, mapping.signature(), clock.instant().minus(Duration.ofHours(1)), response);
        when(repository.read("10-2693", mapping.signature())).thenReturn(Optional.of(cached));
        doThrow(new IOException("disk full")).when(repository).save(any()); var service = service();
        service.get("10-2693"); executor.runNext(); var result = service.get("10-2693");
        assertThat(result.mode()).isEqualTo("live"); assertThat(result.votings()).isEqualTo(response.votings());
        assertThat(result.error()).contains("disk full"); assertThat(result.stale()).isTrue(); assertThat(executor.tasks).isEmpty();
    }
    @Test void replacedMappingPreventsOldBackgroundTaskFromSaving() throws Exception {
        var service = service(); service.get("10-2693");
        when(mappings.read()).thenReturn(new VotingMappingSource.View(Map.of("10-2693",
                new VotingMappingSource.Mapping(snapshot(), "b".repeat(64))), null));
        executor.runNext(); verify(repository, never()).save(any());
        service.get("10-2693"); assertThat(executor.tasks).hasSize(1); executor.runNext();
        verify(repository).save(argThat(c -> c.signature().equals("b".repeat(64))));
    }
    @Test void emptyVerifiedMappingNeverTouchesSourcesOrCache() {
        var empty = new BillVotings.Response("10-2693", "2026-11-15", snapshot().sourceUrl(), null, List.of(), "snapshot", false, null);
        when(mappings.read()).thenReturn(new VotingMappingSource.View(Map.of("10-2693", new VotingMappingSource.Mapping(empty, "empty")), null));
        var result = service().get("10-2693"); assertThat(result.stale()).isFalse(); assertThat(result.votings()).isEmpty();
        verifyNoInteractions(source, repository, bills); assertThat(executor.tasks).isEmpty();
    }
    @Test void invalidCachedMappingFallsBackAndRefreshes() throws Exception {
        var invalid = new BillVotings.Response("10-2693", clock.instant().toString(), snapshot().sourceUrl(), null, List.of(), "live", false, null);
        when(repository.read("10-2693", mapping.signature())).thenReturn(Optional.of(new VotingRepository.Cached(1, mapping.signature(), clock.instant(), invalid)));
        var service = service(); assertThat(service.get("10-2693").error()).contains("Cache"); executor.runNext();
        assertThat(service.get("10-2693").mode()).isEqualTo("live"); assertThat(service.get("10-2693").error()).isNull();
    }
}
