package pl.simplysejm.bills.port;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import pl.simplysejm.bills.api.Bill;

public interface BillRepository {
    record Entry(Bill bill, String pdfHash, String pdfFile) {}
    record Snapshot(int version, List<Entry> entries, Instant lastCheckedAt, Instant lastSuccessAt, String error) {}
    Snapshot load() throws IOException;
    void save(Snapshot snapshot) throws IOException;
    String savePdf(String hash, byte[] bytes) throws IOException;
    Optional<Path> pdf(Entry entry);
}
