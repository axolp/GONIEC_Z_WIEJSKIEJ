package pl.simplysejm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Read-only editorial mappings. An invalid replacement never discards the last valid mapping. */
@Component
public class VotingMappings {
    record Mapping(BillVotings.Response snapshot, String signature) {}
    record View(Map<String, Mapping> bills, String error) {}
    private final ObjectMapper mapper;
    private final Path path;
    private Map<String, Mapping> lastValid = Map.of();
    private String lastStamp;
    static final int MAX_FILE_BYTES = 10 * 1024 * 1024;
    public VotingMappings(ObjectMapper mapper,
            @Value("${bill-votings.path:${BILL_VOTINGS_PATH:../data/bill-votings.json}}") String path) {
        this.mapper = mapper; this.path = Path.of(path);
    }
    synchronized View read() {
        try {
            String stamp = Files.getLastModifiedTime(path) + ":" + Files.size(path);
            if (stamp.equals(lastStamp)) return new View(lastValid, null);
            var file = mapper.readValue(boundedRead(path), BillVotings.File.class);
            VotingValidation.check(file != null && file.bills() != null && file.bills().size() <= 100,
                    "Nieprawidłowa lista powiązań");
            Map<String, Mapping> next = new LinkedHashMap<>();
            for (var snapshot : file.bills()) {
                VotingValidation.validate(snapshot);
                VotingValidation.check("snapshot".equals(snapshot.mode()), "Plik mapowania musi być snapshotem");
                var mapping = new Mapping(snapshot, BillService.sha256(mapper.writeValueAsBytes(snapshot)));
                VotingValidation.check(next.putIfAbsent(snapshot.billId(), mapping) == null, "Powtórzony billId");
            }
            lastValid = Map.copyOf(next); lastStamp = stamp;
            return new View(lastValid, null);
        } catch (IOException | RuntimeException e) {
            return new View(lastValid, "Nie można odczytać zweryfikowanych powiązań głosowań: " + e.getMessage());
        }
    }
    static byte[] boundedRead(Path path) throws IOException {
        if (Files.size(path) > MAX_FILE_BYTES) throw new IOException("Przekroczono limit pliku głosowań");
        try (var in = Files.newInputStream(path)) {
            byte[] bytes = in.readNBytes(MAX_FILE_BYTES + 1);
            if (bytes.length > MAX_FILE_BYTES) throw new IOException("Przekroczono limit pliku głosowań");
            return bytes;
        }
    }
}
