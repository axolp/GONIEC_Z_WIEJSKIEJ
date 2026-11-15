package pl.simplysejm;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import pl.simplysejm.bills.adapter.file.CacheStore;
import pl.simplysejm.bills.adapter.http.SejmClient;
import pl.simplysejm.bills.adapter.pdf.PdfExtractor;
import pl.simplysejm.bills.application.BillService;
import pl.simplysejm.bills.domain.ExtractiveSummarizer;
import pl.simplysejm.bills.domain.PrintSelector;
import pl.simplysejm.bootstrap.SejmProperties;
import pl.simplysejm.infrastructure.http.BoundedHttpTransport;
import pl.simplysejm.votings.adapter.file.VotingCache;
import pl.simplysejm.votings.adapter.file.VotingMappings;
import pl.simplysejm.votings.adapter.http.VotingClient;
import pl.simplysejm.votings.api.BillVotings;
import pl.simplysejm.votings.application.VotingService;
import pl.simplysejm.votings.domain.VotingHtmlParser;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static pl.simplysejm.HtmlVotingFixtures.*;
import static pl.simplysejm.VotingFixtures.MAPPER;

class VotingHtmlFallbackIT {
    @TempDir Path temp;
    VotingService service;
    final BoundedHttpTransport transport = mock(BoundedHttpTransport.class);
    final OrchestrationFixtures.ManualExecutor worker = new OrchestrationFixtures.ManualExecutor();
    final OrchestrationFixtures.MutableClock clock = new OrchestrationFixtures.MutableClock();
    SejmProperties properties() {
        return new SejmProperties("https://api.sejm.gov.pl/sejm/term10/prints", temp.resolve("cache"), temp.resolve("seed.json"),
                30, false, 1, 1, 1024, 4096, 300, 300000, "http://localhost:5173");
    }
    void start() throws Exception {
        var snapshot = new BillVotings.Response("10-2693", "2026-11-15", mapping().sourceUrl(), "10-66-103", List.of(mapping()), "snapshot", false, null);
        Path path = temp.resolve("mapping.json"); MAPPER.writeValue(path.toFile(), new BillVotings.File(List.of(snapshot)));
        var client = new VotingClient(transport, MAPPER, "https://api.sejm.gov.pl/sejm/term10/votings", new VotingHtmlParser(clock), properties());
        var bills = new BillService(properties(), new SejmClient(properties(), MAPPER, transport), new PrintSelector(),
                new PdfExtractor(properties()), new ExtractiveSummarizer(), new CacheStore(MAPPER, properties()), clock, worker);
        service = new VotingService(new VotingMappings(MAPPER, path.toString()), new VotingCache(MAPPER, properties()), client, bills, 30, 60, clock, worker);
    }
    @Test void apiFailureHtmlSuccessPersistsClubOnlyLiveWithSourceAndHonestWarning() throws Exception {
        when(transport.fetch(anyString(), anyInt(), eq("application/json"), any())).thenThrow(new IOException("API unavailable"));
        when(transport.fetch(eq(VotingHtmlParser.sourceUrl(10, 66, 103)), anyInt(), eq("text/html"), any())).thenReturn(html());
        start(); service.get("10-2693");
        worker.runNext();
        var result = service.get("10-2693");
        assertThat(result.error()).contains("Brak danych imiennych");
        assertThat(result.stale()).isTrue();
        assertThat(result.votings().getFirst().votes()).isEmpty();
        assertThat(result.votings().getFirst().clubResults()).hasSize(2);
        String source = result.votings().getFirst().clubResultsSourceUrl();
        clearInvocations(transport); start();
        var cached = service.get("10-2693");
        assertThat(cached.mode()).isEqualTo("live");
        assertThat(cached.stale()).isTrue();
        assertThat(cached.votings().getFirst().clubResultsSourceUrl()).isEqualTo(source);
        assertThat(cached.error()).contains("imiennych"); verifyNoInteractions(transport);
    }
    @Test void failedOrMisidentifiedHtmlReturnsSnapshotAndNeverCreatesLiveCache() throws Exception {
        when(transport.fetch(anyString(), anyInt(), eq("application/json"), any())).thenThrow(new IOException("API unavailable"));
        when(transport.fetch(eq(VotingHtmlParser.sourceUrl(10, 66, 103)), anyInt(), eq("text/html"), any())).thenReturn(replace("nr 103 na 66.", "nr 40 na 68."));
        start(); service.get("10-2693");
        worker.runNext();
        assertThat(service.get("10-2693").mode()).isEqualTo("snapshot");
        assertThat(service.get("10-2693").error()).contains("API unavailable", "HTML");
        assertThat(Files.exists(properties().dataDir().resolve("votings/10-2693.json"))).isFalse();
    }
    @Test void successfulApiRemainsPrimaryAndDoesNotFetchHtml() throws Exception {
        when(transport.fetch(anyString(), anyInt(), eq("application/json"), any())).thenReturn(VotingFixtures.VOTING_JSON.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var client = new VotingClient(transport, MAPPER, "https://api.sejm.gov.pl/sejm/term10/votings", new VotingHtmlParser(clock), properties());
        assertThat(client.fetch(mapping()).votes()).hasSize(1);
        verify(transport, never()).fetch(anyString(), anyInt(), eq("text/html"), any());
    }
    @Test void htmlUrlIsBuiltOnlyFromValidatedIdentity() {
        assertThat(VotingHtmlParser.sourceUrl(10, 68, 40)).isEqualTo("https://www.sejm.gov.pl/Sejm10.nsf/Glosowanie.xsp?posiedzenie=68&glosowanie=40");
        assertThatThrownBy(() -> VotingHtmlParser.sourceUrl(9, 66, 103)).isInstanceOf(IllegalArgumentException.class);
    }
}
