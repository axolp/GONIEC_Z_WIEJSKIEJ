package pl.simplysejm;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class BillService {
    private static final Logger log = LoggerFactory.getLogger(BillService.class);
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
    public record Status(Instant lastCheckedAt, Instant lastSuccessAt, boolean syncing, String error, long intervalMinutes) {}
    private final SejmProperties config;
    private final SejmClient client;
    private final PrintSelector selector;
    private final PdfExtractor extractor;
    private final ExtractiveSummarizer summarizer;
    private final CacheStore store;
    private final AtomicBoolean syncing = new AtomicBoolean();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile CacheStore.Snapshot snapshot;
    public BillService(SejmProperties config, SejmClient client, PrintSelector selector, PdfExtractor extractor,
                       ExtractiveSummarizer summarizer, CacheStore store) throws IOException {
        this.config = config; this.client = client; this.selector = selector; this.extractor = extractor;
        this.summarizer = summarizer; this.store = store; snapshot = store.load();
    }
    @EventListener(ApplicationReadyEvent.class)
    public void startup() { if (config.startupSync()) requestSync(); }
    @Scheduled(fixedDelayString = "${sejm.interval-minutes:30}", initialDelayString = "${sejm.interval-minutes:30}", timeUnit = TimeUnit.MINUTES)
    public void poll() { requestSync(); }
    public boolean requestSync() {
        if (!syncing.compareAndSet(false, true)) return false;
        try { executor.execute(this::synchronize); return true; }
        catch (RejectedExecutionException e) { syncing.set(false); throw e; }
    }
    public List<Bill> bills() {
        return snapshot.entries().stream().map(CacheStore.Entry::bill)
                .sorted(Comparator.comparing(Bill::date).thenComparing(b -> new java.math.BigInteger(b.number())).reversed()).toList();
    }
    public Optional<Bill> bill(String id) { return entry(id).map(CacheStore.Entry::bill); }
    private Optional<CacheStore.Entry> entry(String id) {
        return snapshot.entries().stream().filter(e -> e.bill().id().equals(id)).findFirst();
    }
    public Optional<Path> pdf(String id) { return entry(id).flatMap(store::pdf); }
    public Status status() {
        var current = snapshot;
        return new Status(current.lastCheckedAt(), current.lastSuccessAt(), syncing.get(), current.error(), config.intervalMinutes());
    }
    private void synchronize() {
        Instant checked = Instant.now();
        snapshot = new CacheStore.Snapshot(1, snapshot.entries(), checked, snapshot.lastSuccessAt(), null);
        try {
            PrintDocument latest = selector.latest(client.prints()).orElseThrow(() -> new IOException("Brak projektów ustaw w API Sejmu"));
            PrintDocument detail = client.detail(latest.number());
            if (!selector.isBill(detail) || !latest.number().equals(detail.number())) throw new IOException("Niespójne szczegóły druku z API");
            String attachment = client.attachment(detail);
            byte[] bytes = client.pdf(detail, attachment);
            String hash = sha256(bytes);
            String id = "10-" + detail.number();
            CacheStore.Entry previous = entry(id).orElse(null);
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
            List<CacheStore.Entry> entries = new ArrayList<>(snapshot.entries());
            entries.removeIf(e -> e.bill().id().equals(id));
            entries.add(new CacheStore.Entry(bill, hash, file));
            var next = new CacheStore.Snapshot(1, List.copyOf(entries), checked,
                    extractionError == null ? Instant.now() : snapshot.lastSuccessAt(), extractionError);
            store.save(next);
            snapshot = next;
        } catch (Exception e) {
            log.warn("Sejm synchronization failed", e);
            snapshot = new CacheStore.Snapshot(1, snapshot.entries(), checked, snapshot.lastSuccessAt(),
                    "Synchronizacja nie powiodła się: " + Objects.toString(e.getMessage(), e.getClass().getSimpleName()));
            try { store.save(snapshot); } catch (IOException saveError) { log.error("Cannot persist sync status", saveError); }
        } finally { syncing.set(false); }
    }
    static boolean shouldPreserve(CacheStore.Entry previous, String newHash) {
        // An unbound seed is pinned on first download. It is never permanently exempt from hash checks.
        return previous != null && previous.bill().status().equals("analyzed")
                && (previous.pdfHash() == null || previous.pdfHash().equals(newHash));
    }
    static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    @PreDestroy void close() { executor.shutdownNow(); }
}
