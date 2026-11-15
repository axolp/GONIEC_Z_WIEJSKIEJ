package pl.simplysejm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Public voting contract. Missing data remains null/empty, never inferred. */
public final class BillVotings {
    private BillVotings() {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Response(String billId, String verifiedAt, String sourceUrl, String defaultVotingId,
                           List<Voting> votings, String mode, boolean stale, String error) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Voting(String id, int term, int sitting, int votingNumber, String date, String title,
                         String description, String category, String sourceUrl, String pdfUrl,
                         Integer yes, Integer no, Integer abstain, Integer notParticipating,
                         Integer totalVoted, List<Vote> votes,
                         @JsonInclude(JsonInclude.Include.NON_NULL) List<ClubResult> clubResults,
                         @JsonInclude(JsonInclude.Include.NON_NULL) String clubResultsSourceUrl,
                         @JsonInclude(JsonInclude.Include.NON_NULL) String clubResultsVerifiedAt) {
        public Voting(String id, int term, int sitting, int votingNumber, String date, String title,
                      String description, String category, String sourceUrl, String pdfUrl,
                      Integer yes, Integer no, Integer abstain, Integer notParticipating,
                      Integer totalVoted, List<Vote> votes) {
            this(id, term, sitting, votingNumber, date, title, description, category, sourceUrl, pdfUrl,
                    yes, no, abstain, notParticipating, totalVoted, votes, null, null, null);
        }
    }
    public record ClubResult(String club, Integer members, Integer totalVoted, Integer yes, Integer no, Integer abstain, Integer notParticipating) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Vote(@JsonProperty("MP") int mp, String firstName, String lastName, String club, String vote) {}
    public record File(List<Response> bills) {}
}
