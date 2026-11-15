package pl.simplysejm.bills.api;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

public interface BillQuery {
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
    record Status(Instant lastCheckedAt, Instant lastSuccessAt, boolean syncing, String error, long intervalMinutes) {}
    List<Bill> bills();
    Optional<Bill> bill(String id);
    Optional<Path> pdf(String id);
    Status status();
}
