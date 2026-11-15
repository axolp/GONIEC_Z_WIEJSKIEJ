package pl.simplysejm.votings.port;

import java.util.Map;
import pl.simplysejm.votings.api.BillVotings;

public interface VotingMappingSource {
    record Mapping(BillVotings.Response snapshot, String signature) {}
    record View(Map<String, Mapping> bills, String error) {}
    View read();
}
