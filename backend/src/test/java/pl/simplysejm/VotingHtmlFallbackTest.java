package pl.simplysejm;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;
import static pl.simplysejm.HtmlVotingFixtures.*;
import static pl.simplysejm.VotingFixtures.MAPPER;

class VotingHtmlFallbackTest {
    @TempDir Path temp;
    VotingService service;
    final SejmClient transport = mock(SejmClient.class);
    SejmProperties properties() {
        return new SejmProperties("https://api.sejm.gov.pl/sejm/term10/prints", temp.resolve("cache"), temp.resolve("seed.json"),
                30, false, 1, 1, 1024, 4096, 300, 300000, "http://localhost:5173");
    }
    void start() throws Exception {
        var snapshot = new BillVotings.Response("10-2693", "2026-11-15", mapping().sourceUrl(), "10-66-103", List.of(mapping()), "snapshot", false, null);
        Path path = temp.resolve("mapping.json"); MAPPER.writeValue(path.toFile(), new BillVotings.File(List.of(snapshot)));
        var client = new VotingClient(transport, MAPPER, "https://api.sejm.gov.pl/sejm/term10/votings");
        service = new VotingService(new VotingMappings(MAPPER, path.toString()), new VotingCache(MAPPER, properties()), client, mock(BillService.class), 30, 60);
    }
    @AfterEach void close() { if (service != null) service.close(); }
    @Test void apiFailureHtmlSuccessPersistsClubOnlyLiveWithSourceAndHonestWarning() throws Exception {
        when(transport.json(anyString())).thenThrow(new IOException("API unavailable"));
        when(transport.votingHtml(10, 66, 103)).thenReturn(html());
        start(); service.get("10-2693");
        await().atMost(Duration.ofSeconds(3)).until(() -> service.get("10-2693").mode().equals("live"));
        var result = service.get("10-2693");
        assertThat(result.error()).contains("Brak danych imiennych");
        assertThat(result.votings().getFirst().votes()).isEmpty();
        assertThat(result.votings().getFirst().clubResults()).hasSize(2);
        String source = result.votings().getFirst().clubResultsSourceUrl();
        service.close(); clearInvocations(transport); start();
        var cached = service.get("10-2693");
        assertThat(cached.mode()).isEqualTo("live");
        assertThat(cached.votings().getFirst().clubResultsSourceUrl()).isEqualTo(source);
        assertThat(cached.error()).contains("imiennych"); verifyNoInteractions(transport);
    }
    @Test void failedOrMisidentifiedHtmlReturnsSnapshotAndNeverCreatesLiveCache() throws Exception {
        when(transport.json(anyString())).thenThrow(new IOException("API unavailable"));
        when(transport.votingHtml(10, 66, 103)).thenReturn(replace("nr 103 na 66.", "nr 40 na 68."));
        start(); service.get("10-2693");
        await().atMost(Duration.ofSeconds(3)).until(() -> service.get("10-2693").error() != null);
        assertThat(service.get("10-2693").mode()).isEqualTo("snapshot");
        assertThat(service.get("10-2693").error()).contains("API unavailable", "HTML");
        assertThat(Files.exists(properties().dataDir().resolve("votings/10-2693.json"))).isFalse();
    }
    @Test void successfulApiRemainsPrimaryAndDoesNotFetchHtml() throws Exception {
        when(transport.json(anyString())).thenReturn(VotingClientTest.JSON.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var client = new VotingClient(transport, MAPPER, "https://api.sejm.gov.pl/sejm/term10/votings");
        assertThat(client.fetch(mapping()).votes()).hasSize(1);
        verify(transport, never()).votingHtml(anyInt(), anyInt(), anyInt());
    }
    @Test void htmlUrlIsBuiltOnlyFromValidatedIdentity() {
        assertThat(VotingHtmlParser.sourceUrl(10, 68, 40)).isEqualTo("https://www.sejm.gov.pl/Sejm10.nsf/Glosowanie.xsp?posiedzenie=68&glosowanie=40");
        assertThatThrownBy(() -> VotingHtmlParser.sourceUrl(9, 66, 103)).isInstanceOf(IllegalArgumentException.class);
    }
}
