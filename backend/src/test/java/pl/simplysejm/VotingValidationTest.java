package pl.simplysejm;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import pl.simplysejm.votings.api.BillVotings;
import pl.simplysejm.votings.domain.VotingValidation;

import static org.assertj.core.api.Assertions.*;
import static pl.simplysejm.VotingFixtures.*;

class VotingValidationTest {
    @Test void acceptsAuthoritativeFormatWithoutRollCallOrKnownAbsence() throws Exception {
        var snapshot = snapshot();
        VotingValidation.validate(snapshot);
        var serialized = MAPPER.readTree(MAPPER.writeValueAsBytes(snapshot));
        assertThat(serialized.get("votings").get(0).get("notParticipating").isNull()).isTrue();
        assertThat(serialized.get("votings").get(0).get("votes").isEmpty()).isTrue();
        assertThat(serialized.has("error")).isTrue();
    }
    @Test void rejectsContradictoryTotalsRollCallAndDuplicateMembers() {
        var v = live();
        var badTotal = new BillVotings.Voting(v.id(), 10, 66, 103, v.date(), v.title(), v.description(), v.category(),
                v.sourceUrl(), v.pdfUrl(), 2, 0, 0, 0, 1, v.votes());
        assertThatThrownBy(() -> VotingValidation.validate(badTotal)).isInstanceOf(IOException.class).hasMessageContaining("suma");
        var wrongVote = voting("10-66-103", "final", null, List.of(new BillVotings.Vote(1, "Jan", "Kowalski", "KO", "NO")));
        assertThatThrownBy(() -> VotingValidation.validate(wrongVote)).isInstanceOf(IOException.class).hasMessageContaining("imienny");
        var duplicated = voting("10-66-103", "final", null, List.of(v.votes().getFirst(), v.votes().getFirst()));
        assertThatThrownBy(() -> VotingValidation.validate(duplicated)).isInstanceOf(IOException.class).hasMessageContaining("MP");
    }
    @Test void doesNotNormalizeNoVoteIntoAbsent() throws Exception {
        var vote = new BillVotings.Vote(2, "Anna", "Nowak", null, "NO_VOTE");
        var v = voting("10-66-103", "final", null, List.of(live().votes().getFirst(), vote));
        VotingValidation.validate(v);
        var json = MAPPER.readTree(MAPPER.writeValueAsBytes(v));
        assertThat(json.get("votes").get(1).get("MP").intValue()).isEqualTo(2);
        assertThat(json.get("votes").get(1).get("vote").textValue()).isEqualTo("NO_VOTE");
        assertThat(json.get("notParticipating").isNull()).isTrue();
    }
    @Test void validatesDefaultIdentityAndDoesNotInferFinalCategory() {
        var s = snapshot();
        var invalid = new BillVotings.Response(s.billId(), s.verifiedAt(), s.sourceUrl(), "10-66-999", s.votings(), s.mode(), false, null);
        assertThatThrownBy(() -> VotingValidation.validate(invalid)).isInstanceOf(IOException.class).hasMessageContaining("defaultVotingId");
        var wrong = new BillVotings.Voting("10-66-104", 10, 66, 103, "2026-10-08", "Ustawa", "", "final", s.sourceUrl(), s.sourceUrl(), 1, 0, 0, null, 1, List.of());
        assertThatThrownBy(() -> VotingValidation.validate(wrong)).isInstanceOf(IOException.class).hasMessageContaining("ID");
        assertThat(voting("10-66-104", "amendment", null, List.of()).category()).isEqualTo("amendment");
    }
}
