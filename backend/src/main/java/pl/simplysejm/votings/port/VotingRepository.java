package pl.simplysejm.votings.port;

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;
import pl.simplysejm.votings.api.BillVotings;

public interface VotingRepository {
    record Cached(int version, String signature, Instant fetchedAt, BillVotings.Response response) {}
    Optional<Cached> read(String billId, String signature) throws IOException;
    void save(Cached cached) throws IOException;
}
