package pl.simplysejm.votings.adapter.file;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.springframework.stereotype.Component;
import pl.simplysejm.bootstrap.SejmProperties;
import pl.simplysejm.votings.domain.VotingValidation;
import pl.simplysejm.votings.port.VotingRepository;

@Component
public class VotingCache implements VotingRepository {
    private final ObjectMapper mapper;
    private final Path directory;
    public VotingCache(ObjectMapper mapper, SejmProperties config) {
        this.mapper = mapper; this.directory = config.dataDir().resolve("votings");
    }
    public Optional<Cached> read(String billId, String signature) throws IOException {
        Path path = path(billId);
        if (!Files.exists(path)) return Optional.empty();
        Cached cached = mapper.readValue(VotingMappings.boundedRead(path), Cached.class);
        VotingValidation.check(cached != null && cached.version() == 1 && cached.fetchedAt() != null, "Nieprawidłowy cache głosowań");
        VotingValidation.validate(cached.response());
        VotingValidation.check(billId.equals(cached.response().billId()) && "live".equals(cached.response().mode()), "Cache innej ustawy lub niewłaściwego trybu");
        VotingValidation.check(cached.signature() != null && cached.signature().matches("[a-f0-9]{64}"), "Nieprawidłowy podpis mapowania cache");
        for (var voting : cached.response().votings())
            VotingValidation.check(VotingValidation.hasLiveData(voting), "Cache live nie zawiera potwierdzonych wyników");
        if (signature != null && !Objects.equals(signature, cached.signature())) return Optional.empty();
        return Optional.of(cached);
    }
    public void save(Cached cached) throws IOException {
        VotingValidation.validate(cached.response());
        for (var voting : cached.response().votings())
            VotingValidation.check(VotingValidation.hasLiveData(voting), "Nie można zapisać live bez potwierdzonych wyników");
        Files.createDirectories(directory);
        Path target = path(cached.response().billId());
        Path temp = Files.createTempFile(directory, ".votings-", ".tmp");
        try {
            Files.write(temp, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(cached));
            try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
    private Path path(String billId) throws IOException {
        VotingValidation.check(VotingValidation.validBillId(billId), "Nieprawidłowy billId cache");
        return directory.resolve(billId + ".json");
    }
}
