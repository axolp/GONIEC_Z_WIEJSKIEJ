package pl.simplysejm;

import java.io.IOException;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.*;
import pl.simplysejm.bills.api.Bill;
import pl.simplysejm.bills.application.BillService;
import pl.simplysejm.bills.domain.*;
import pl.simplysejm.bills.port.*;
import pl.simplysejm.bootstrap.SejmProperties;
import pl.simplysejm.infrastructure.files.Sha256;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BillServiceTest {
    final PrintSource source = mock(PrintSource.class);
    final PdfTextExtractor extractor = mock(PdfTextExtractor.class);
    final BillRepository repository = mock(BillRepository.class);
    final OrchestrationFixtures.MutableClock clock = new OrchestrationFixtures.MutableClock();
    final OrchestrationFixtures.ManualExecutor executor = new OrchestrationFixtures.ManualExecutor();
    final byte[] pdf = "%PDF-synthetic".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    final PrintDocument print = new PrintDocument("3355", 10, "Rządowy projekt ustawy", LocalDate.of(2026, 11, 13), null, List.of("3355.pdf"));
    BillRepository.Entry seed() {
        return new BillRepository.Entry(new Bill("10-3355", "3355", 10, print.title(), print.deliveryDate(),
                "https://www.sejm.gov.pl/sejm10.nsf/druk.xsp?nr=3355", "/api/bills/10-3355/pdf", "analyzed",
                new Bill.Summary("Ręczny skrót", List.of("Zmiana"), List.of(), "Nie ustalono", "Projekt", "manual"), null), null, null);
    }
    SejmProperties properties() {
        return new SejmProperties("https://unused.invalid", Path.of("unused"), Path.of("unused"), 30, false, 1, 1, 1024, 4096, 300, 300000, "http://localhost:5173");
    }
    @BeforeEach void setup() throws Exception {
        when(repository.load()).thenReturn(new BillRepository.Snapshot(1, List.of(seed()), null, Instant.EPOCH, null));
        when(source.prints()).thenReturn(List.of(print)); when(source.detail("3355")).thenReturn(print);
        when(source.attachment(print)).thenReturn("3355.pdf"); when(source.pdf(print, "3355.pdf")).thenReturn(pdf);
        when(repository.savePdf(anyString(), any())).thenAnswer(call -> "pdf/" + call.getArgument(0) + ".pdf");
    }
    BillService service() throws IOException {
        return new BillService(properties(), source, new PrintSelector(), extractor, new ExtractiveSummarizer(), repository, clock, executor);
    }
    @Test void deduplicatesAndPinsUnboundSeedWithoutOverwritingAnalysis() throws Exception {
        var service = service(); assertThat(service.requestSync()).isTrue(); assertThat(service.requestSync()).isFalse();
        assertThat(service.status().syncing()).isTrue(); verifyNoInteractions(source); executor.runNext();
        assertThat(service.status().syncing()).isFalse(); assertThat(service.status().lastSuccessAt()).isEqualTo(clock.instant());
        assertThat(service.bill("10-3355").orElseThrow().summary().method()).isEqualTo("manual");
        verifyNoInteractions(extractor); verify(repository).savePdf(Sha256.digest(pdf), pdf);
        assertThat(service.requestSync()).isTrue(); executor.runNext(); verifyNoInteractions(extractor);
    }
    @Test void rejectionResetsSyncFlagAndAllowsRetry() throws Exception {
        var service = new BillService(properties(), source, new PrintSelector(), extractor, new ExtractiveSummarizer(), repository, clock,
                task -> { throw new RejectedExecutionException("full"); });
        assertThatThrownBy(service::requestSync).isInstanceOf(RejectedExecutionException.class);
        assertThat(service.status().syncing()).isFalse();
        assertThatThrownBy(service::requestSync).isInstanceOf(RejectedExecutionException.class);
    }
    @Test void downloadFailurePreservesPreviousDataAndSuccessTime() throws Exception {
        when(source.pdf(print, "3355.pdf")).thenThrow(new IOException("offline")); var service = service();
        service.requestSync(); executor.runNext();
        assertThat(service.bills()).containsExactly(seed().bill()); assertThat(service.status().lastSuccessAt()).isEqualTo(Instant.EPOCH);
        assertThat(service.status().error()).contains("offline"); assertThat(service.status().syncing()).isFalse();
        verify(repository, never()).savePdf(anyString(), any());
    }
    @Test void saveFailureDoesNotPublishUncommittedData() throws Exception {
        when(repository.load()).thenReturn(new BillRepository.Snapshot(1, List.of(), null, Instant.EPOCH, null));
        when(extractor.extract(pdf)).thenReturn(new PdfTextExtractor.Extraction("", false));
        doThrow(new IOException("disk full")).when(repository).save(any()); var service = service();
        service.requestSync(); executor.runNext();
        assertThat(service.bills()).isEmpty(); assertThat(service.status().error()).contains("disk full");
        assertThat(service.status().lastSuccessAt()).isEqualTo(Instant.EPOCH); assertThat(service.status().syncing()).isFalse();
    }
    @Test void changedHashReanalyzesAndExtractionFailureRemainsPending() throws Exception {
        when(repository.load()).thenReturn(new BillRepository.Snapshot(1,
                List.of(new BillRepository.Entry(seed().bill(), Sha256.digest("old".getBytes()), "old.pdf")), null, Instant.EPOCH, null));
        when(extractor.extract(pdf)).thenThrow(new IOException("bad pdf")); var service = service();
        service.requestSync(); executor.runNext(); verify(extractor).extract(pdf);
        assertThat(service.bill("10-3355").orElseThrow().status()).isEqualTo("pending");
        assertThat(service.status().error()).contains("PDF"); assertThat(service.status().lastSuccessAt()).isEqualTo(Instant.EPOCH);
    }
    @Test void pdfSaveFailurePreservesPreviousBill() throws Exception {
        when(repository.savePdf(anyString(), any())).thenThrow(new IOException("pdf disk")); var service = service();
        service.requestSync(); executor.runNext(); assertThat(service.bills()).containsExactly(seed().bill());
        assertThat(service.status().error()).contains("pdf disk");
    }
}
