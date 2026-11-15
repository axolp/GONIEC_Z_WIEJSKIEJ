package pl.simplysejm.bills.application;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import pl.simplysejm.bills.api.*;
import pl.simplysejm.bills.api.Bill;
import pl.simplysejm.bills.domain.ExtractiveSummarizer;
import pl.simplysejm.bills.domain.PrintSelector;
import pl.simplysejm.bills.port.*;
import pl.simplysejm.bills.port.BillRepository;
import pl.simplysejm.bills.port.PrintDocument;
import pl.simplysejm.bootstrap.SejmProperties;
import pl.simplysejm.infrastructure.files.Sha256;

@Service
public class BillService implements BillQuery, BillSync {
    private static final Logger log = LoggerFactory.getLogger(BillService.class);
    private final SejmProperties config;
    private final PrintSource client;
    private final PrintSelector selector;
    private final PdfTextExtractor extractor;
    private final ExtractiveSummarizer summarizer;
    private final BillRepository store;
    private final AtomicBoolean syncing = new AtomicBoolean();
    private final Executor executor;
    private final Clock clock;
    private volatile BillRepository.Snapshot snapshot;
    public BillService(SejmProperties config, PrintSource client, PrintSelector selector, PdfTextExtractor extractor,
                       ExtractiveSummarizer summarizer, BillRepository store, Clock clock,
                       @Qualifier("billExecutor") Executor executor) throws IOException {
        this.clock = clock; this.executor = executor;
        this.config = config; this.client = client; this.selector = selector; this.extractor = extractor;
        this.summarizer = summarizer; this.store = store; snapshot = store.load();
    }
    public boolean requestSync() {
        if (!syncing.compareAndSet(false, true)) return false;
        try { executor.execute(this::synchronize); return true; }
        catch (RejectedExecutionException e) { syncing.set(false); throw e; }
    }
    public List<Bill> bills() {
        return snapshot.entries().stream().map(BillRepository.Entry::bill)
                .sorted(Comparator.comparing(Bill::date).thenComparing(b -> new java.math.BigInteger(b.number())).reversed()).toList();
    }
    public Optional<Bill> bill(String id) { return entry(id).map(BillRepository.Entry::bill); }
    private Optional<BillRepository.Entry> entry(String id) {
        return snapshot.entries().stream().filter(e -> e.bill().id().equals(id)).findFirst();
    }
    public Optional<Path> pdf(String id) { return entry(id).flatMap(store::pdf); }
    public Status status() {
        var current = snapshot;
        return new Status(current.lastCheckedAt(), current.lastSuccessAt(), syncing.get(), current.error(), config.intervalMinutes());
    }
    private void synchronize() {
        Instant checked = clock.instant();
        snapshot = new BillRepository.Snapshot(1, snapshot.entries(), checked, snapshot.lastSuccessAt(), null);
        try {
            PrintDocument latest = selector.latest(client.prints()).orElseThrow(() -> new IOException("Brak projektów ustaw w API Sejmu"));
            PrintDocument detail = client.detail(latest.number());
            if (!selector.isBill(detail) || !latest.number().equals(detail.number())) throw new IOException("Niespójne szczegóły druku z API");
            String attachment = client.attachment(detail);
            byte[] bytes = client.pdf(detail, attachment);
            String hash = Sha256.digest(bytes);
            String id = "10-" + detail.number();
            BillRepository.Entry previous = entry(id).orElse(null);
            boolean preserve = shouldPreserve(previous, hash);
            Bill.Summary summary;
            String preview = previous == null ? null : previous.bill().textPreview();
            String status;
            String extractionError = null;
            if (preserve) { summary = previous.bill().summary(); status = previous.bill().status(); }
            else {
                try {
                    var text = extractor.extract(bytes);
                    summary = summarizer.summarize(text.text(), text.truncated());
                    status = summarizer.hasText(text.text()) && !summary.lead().equals("Brak lokalnej analizy tekstu projektu.") ? "analyzed" : "pending";
                    preview = text.text().isBlank() ? null : text.text().substring(0, Math.min(1500, text.text().length()));
                    if (status.equals("pending")) extractionError = "PDF nie zawiera tekstu wystarczającego do analizy; OCR niedostępny";
                } catch (IOException | RuntimeException e) {
                    log.warn("PDF extraction failed for {}", id, e);
                    summary = summarizer.pending("Nie udało się odczytać tekstu PDF; sprawdź dokument źródłowy.");
                    preview = null; status = "pending"; extractionError = "Nie udało się odczytać tekstu PDF";
                }
            }
            Bill bill = new Bill(id, detail.number(), 10, detail.title(), detail.deliveryDate(),
                    "https://www.sejm.gov.pl/sejm10.nsf/druk.xsp?nr=" + detail.number(),
                    "/api/bills/" + id + "/pdf", status, summary, preview);
            String file = store.savePdf(hash, bytes);
            List<BillRepository.Entry> entries = new ArrayList<>(snapshot.entries());
            entries.removeIf(e -> e.bill().id().equals(id));
            entries.add(new BillRepository.Entry(bill, hash, file));
            var next = new BillRepository.Snapshot(1, List.copyOf(entries), checked,
                    extractionError == null ? clock.instant() : snapshot.lastSuccessAt(), extractionError);
            store.save(next);
            snapshot = next;
        } catch (Exception e) {
            log.warn("Sejm synchronization failed", e);
            snapshot = new BillRepository.Snapshot(1, snapshot.entries(), checked, snapshot.lastSuccessAt(),
                    "Synchronizacja nie powiodła się: " + Objects.toString(e.getMessage(), e.getClass().getSimpleName()));
            try { store.save(snapshot); } catch (IOException saveError) { log.error("Cannot persist sync status", saveError); }
        } finally { syncing.set(false); }
    }
    public static boolean shouldPreserve(BillRepository.Entry previous, String newHash) {
        // An unbound seed is pinned on first download. It is never permanently exempt from hash checks.
        return previous != null && previous.bill().status().equals("analyzed")
                && (previous.pdfHash() == null || previous.pdfHash().equals(newHash));
    }
}
