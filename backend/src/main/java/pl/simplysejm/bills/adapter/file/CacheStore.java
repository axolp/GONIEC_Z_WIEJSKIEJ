package pl.simplysejm.bills.adapter.file;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import pl.simplysejm.bills.api.Bill;
import pl.simplysejm.bills.port.BillRepository;
import pl.simplysejm.bootstrap.SejmProperties;

@Component
public class CacheStore implements BillRepository {
    private static final Logger log = LoggerFactory.getLogger(CacheStore.class);
    private final ObjectMapper mapper;
    private final Path directory;
    private final Path seed;
    public CacheStore(ObjectMapper mapper, SejmProperties config) throws IOException {
        this.mapper = mapper; directory = config.dataDir().toAbsolutePath().normalize(); seed = config.seedPath();
        Files.createDirectories(directory.resolve("pdf"));
    }
    public Snapshot load() throws IOException {
        Path cache = directory.resolve("cache.json");
        String warning = null;
        if (Files.exists(cache)) {
            try {
                Snapshot saved = mapper.readValue(cache.toFile(), Snapshot.class);
                if (saved.version() != 1 || saved.entries() == null) throw new IOException("Nieobsługiwana wersja cache");
                for (Entry entry : saved.entries()) validate(entry.bill());
                return saved;
            } catch (IOException | RuntimeException e) {
                warning = "Nie udało się odczytać cache; użyto seedu. " + e.getMessage();
                log.warn("Cannot read cache", e);
                Files.copy(cache, directory.resolve("cache-corrupt-" + System.currentTimeMillis() + ".json"));
            }
        }
        List<Entry> entries = new ArrayList<>();
        if (Files.exists(seed)) {
            Bill.Bills bills = mapper.readValue(seed.toFile(), Bill.Bills.class);
            if (bills.bills() == null) throw new IOException("Seed musi zawierać bills: []");
            Set<String> ids = new HashSet<>();
            for (Bill bill : bills.bills()) {
                validate(bill);
                if (!ids.add(bill.id())) throw new IOException("Powtórzony identyfikator w seed: " + bill.id());
                entries.add(new Entry(bill, null, null));
            }
        }
        return new Snapshot(1, List.copyOf(entries), null, null, warning);
    }
    private void validate(Bill bill) throws IOException {
        if (bill == null || bill.number() == null || !bill.number().matches("[0-9]+") || bill.term() != 10
                || !Objects.equals(bill.id(), "10-" + bill.number()) || bill.title() == null || bill.date() == null
                || bill.sourceUrl() == null || bill.pdfUrl() == null || bill.summary() == null
                || !Set.of("analyzed", "pending").contains(Objects.toString(bill.status(), ""))
                || bill.summary().lead() == null || bill.summary().keyChanges() == null
                || bill.summary().affectedGroups() == null || bill.summary().effectiveDate() == null
                || bill.summary().caveats() == null || bill.summary().method() == null)
            throw new IOException("Nieprawidłowy rekord Bill w seed/cache");
    }
    public synchronized void save(Snapshot snapshot) throws IOException {
        atomicWrite(directory.resolve("cache.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(snapshot));
    }
    public String savePdf(String hash, byte[] bytes) throws IOException {
        if (!hash.matches("[a-f0-9]{64}")) throw new IOException("Nieprawidłowy hash PDF");
        String file = "pdf/" + hash + ".pdf";
        Path path = directory.resolve(file);
        // Content-addressed name allows the old JSON and old PDF to stay consistent during a crash.
        if (!Files.exists(path)) atomicWrite(path, bytes);
        return file;
    }
    public Optional<Path> pdf(Entry entry) {
        if (entry.pdfHash() == null || !entry.pdfHash().matches("[a-f0-9]{64}")
                || !Objects.equals(entry.pdfFile(), "pdf/" + entry.pdfHash() + ".pdf")) return Optional.empty();
        Path path = directory.resolve(entry.pdfFile());
        return Files.isRegularFile(path) ? Optional.of(path) : Optional.empty();
    }
    private void atomicWrite(Path target, byte[] bytes) throws IOException {
        Path temp = Files.createTempFile(target.getParent(), ".write-", ".tmp");
        try {
            Files.write(temp, bytes);
            try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
}
