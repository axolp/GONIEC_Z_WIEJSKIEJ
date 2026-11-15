package pl.simplysejm;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;

final class VotingFixtures {
    static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    static BillVotings.Voting voting(String id, String category, Integer absent, List<BillVotings.Vote> votes) {
        int number = Integer.parseInt(id.substring(id.lastIndexOf('-') + 1));
        return new BillVotings.Voting(id, 10, 66, number, "2026-10-08T20:54:10", "Projekt ustawy", "Głosowanie",
                category, "https://api.sejm.gov.pl/sejm/term10/votings/66/" + number,
                "https://api.sejm.gov.pl/sejm/term10/votings/66/" + number + "/pdf", 1, 0, 0, absent, 1, votes);
    }
    static BillVotings.Response snapshot() {
        return new BillVotings.Response("10-2693", "2026-11-15", "https://www.sejm.gov.pl/sejm10.nsf/PrzebiegProc.xsp?nr=2693",
                "10-66-103", List.of(voting("10-66-103", "final", null, List.of())), "snapshot", false, null);
    }
    static BillVotings.Voting live() {
        return voting("10-66-103", "final", 0, List.of(new BillVotings.Vote(1, "Jan", "Kowalski", "KO", "YES")));
    }
}
