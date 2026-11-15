package pl.simplysejm;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;
import static pl.simplysejm.VotingFixtures.*;

class VotingServiceTest {
    @TempDir Path temp;
    VotingService service;
    final VotingClient client = mock(VotingClient.class);
    final BillService bills = mock(BillService.class);
    Path mappingFile() { return temp.resolve("bill-votings.json"); }
    SejmProperties properties() {
        return new SejmProperties("https://api.sejm.gov.pl/sejm/term10/prints", temp.resolve("cache"), temp.resolve("seed.json"),
                30, false, 1, 1, 1024, 4096, 300, 300000, "http://localhost:5173");
    }
    void write(BillVotings.Response snapshot) throws Exception { MAPPER.writeValue(mappingFile().toFile(), new BillVotings.File(List.of(snapshot))); }
    void start() {
        service = new VotingService(new VotingMappings(MAPPER, mappingFile().toString()), new VotingCache(MAPPER, properties()), client, bills, 30, 60);
    }
    @AfterEach void stop() { if (service != null) service.close(); }
    @Test void servesSnapshotImmediatelyRefreshesOnceAndPersistsLiveAcrossRestart() throws Exception {
        write(snapshot());
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        when(client.fetch(any())).thenAnswer(call -> { entered.countDown(); if (!release.await(3, TimeUnit.SECONDS)) throw new IOException("test deadline"); return live(); });
        start();
        try {
            var first = service.get("10-2693");
            assertThat(first.mode()).isEqualTo("snapshot");
            assertThat(first.stale()).isTrue();
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(service.get("10-2693").votings().getFirst().votes()).isEmpty();
            verify(client, times(1)).fetch(any());
        } finally { release.countDown(); }
        await().atMost(Duration.ofSeconds(3)).until(() -> service.get("10-2693").mode().equals("live"));
        var live = service.get("10-2693");
        assertThat(live.stale()).isFalse(); assertThat(live.error()).isNull();
        assertThat(Instant.parse(live.verifiedAt())).isBeforeOrEqualTo(Instant.now());
        service.close(); clearInvocations(client); start();
        assertThat(service.get("10-2693").votings().getFirst().votes()).hasSize(1);
        verifyNoInteractions(client);
    }
    @Test void failedNetworkReturnsHonestSnapshotAndDoesNotWriteCache() throws Exception {
        write(snapshot()); when(client.fetch(any())).thenThrow(new IOException("timeout")); start();
        service.get("10-2693");
        await().atMost(Duration.ofSeconds(3)).until(() -> service.get("10-2693").error() != null);
        var result = service.get("10-2693");
        assertThat(result.mode()).isEqualTo("snapshot"); assertThat(result.stale()).isTrue();
        assertThat(result.error()).contains("timeout"); assertThat(result.votings()).hasSize(1);
        assertThat(Files.exists(properties().dataDir().resolve("votings/10-2693.json"))).isFalse();
    }
    @Test void staleDiskLiveSurvivesFailureAndFileIsNotDeleted() throws Exception {
        write(snapshot());
        var mappings = new VotingMappings(MAPPER, mappingFile().toString());
        var mapping = mappings.read().bills().get("10-2693");
        var response = new BillVotings.Response("10-2693", "2026-11-15T00:00:00Z", snapshot().sourceUrl(), "10-66-103", List.of(live()), "live", false, null);
        var cache = new VotingCache(MAPPER, properties());
        cache.save(new VotingCache.Cached(1, mapping.signature(), Instant.EPOCH, response));
        Path path = properties().dataDir().resolve("votings/10-2693.json"); byte[] original = Files.readAllBytes(path);
        when(client.fetch(any())).thenThrow(new IOException("offline")); start(); service.get("10-2693");
        await().atMost(Duration.ofSeconds(3)).until(() -> service.get("10-2693").error() != null);
        assertThat(service.get("10-2693").mode()).isEqualTo("live");
        assertThat(service.get("10-2693").stale()).isTrue();
        assertThat(Files.readAllBytes(path)).isEqualTo(original);
    }
    @Test void distinguishesKnownUnmappedUnknownAndUnavailableMappings() throws Exception {
        MAPPER.writeValue(mappingFile().toFile(), new BillVotings.File(List.of()));
        when(bills.bill("10-3355")).thenReturn(Optional.of(mock(Bill.class))); start();
        assertThat(service.get("10-3355").votings()).isEmpty();
        assertThat(service.get("10-3355").stale()).isFalse();
        assertThatThrownBy(() -> service.get("10-999999")).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode().value()).isEqualTo(404));
        Files.delete(mappingFile());
        assertThatThrownBy(() -> service.get("10-3355")).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode().value()).isEqualTo(503));
    }
    @Test void invalidReplacementKeepsLastMappingAndCategoryDefaultAreEditorial() throws Exception {
        var s = snapshot();
        var mapped = new BillVotings.Response(s.billId(), s.verifiedAt(), s.sourceUrl(), "10-66-103", List.of(
                voting("10-66-103", "final", null, List.of()), voting("10-66-104", "amendment", null, List.of())), "snapshot", false, null);
        write(mapped); when(client.fetch(any())).thenThrow(new IOException("offline")); start();
        service.get("10-2693"); Files.writeString(mappingFile(), "{invalid");
        var result = service.get("10-2693");
        assertThat(result.defaultVotingId()).isEqualTo("10-66-103");
        assertThat(result.votings().getLast().category()).isEqualTo("amendment");
        assertThat(result.error()).contains("powiązań"); assertThat(result.stale()).isTrue();
    }
}
