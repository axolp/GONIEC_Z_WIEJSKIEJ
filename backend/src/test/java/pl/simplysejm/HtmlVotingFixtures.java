package pl.simplysejm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Synthetic HTML only: never use these numbers as real Sejm results. */
final class HtmlVotingFixtures {
    static byte[] html() throws IOException {
        try (var in = HtmlVotingFixtures.class.getResourceAsStream("/votings/synthetic-club-results.html")) {
            if (in == null) throw new IOException("Missing synthetic test fixture");
            return in.readAllBytes();
        }
    }
    static byte[] replace(String from, String to) throws IOException {
        return new String(html(), StandardCharsets.UTF_8).replace(from, to).getBytes(StandardCharsets.UTF_8);
    }
    static BillVotings.Voting mapping() {
        var v = VotingFixtures.snapshot().votings().getFirst();
        return new BillVotings.Voting(v.id(), v.term(), v.sitting(), v.votingNumber(), v.date(),
                "Projekt ustawy — druk 2693", v.description(), v.category(),
                "https://www.sejm.gov.pl/sejm10.nsf/PrzebiegProc.xsp?nr=2693", v.pdfUrl(),
                v.yes(), v.no(), v.abstain(), null, v.totalVoted(), v.votes());
    }
}
