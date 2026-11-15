package pl.simplysejm;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import static org.assertj.core.api.Assertions.*;
import static pl.simplysejm.HtmlVotingFixtures.*;

class VotingHtmlParserTest {
    final VotingHtmlParser parser = new VotingHtmlParser();
    @Test void readsHeaderMappedColumnsAndNumericDashesWithoutInventingMembers() throws Exception {
        var result = parser.parse(html(), mapping());
        assertThat(result.votes()).isEmpty();
        assertThat(result.clubResults()).hasSize(2);
        assertThat(result.clubResults().getFirst()).isEqualTo(new BillVotings.ClubResult("KlubTestowyA", 1, 1, 1, 0, 0, 0));
        assertThat(result.clubResults().getLast().notParticipating()).isEqualTo(1);
        assertThat(result.clubResultsSourceUrl()).isEqualTo(VotingHtmlParser.sourceUrl(10, 66, 103));
        assertThat(result.clubResultsVerifiedAt()).isNotBlank();
        assertThat(result.notParticipating()).isEqualTo(1);
        assertThat(result.category()).isEqualTo("final");
    }
    @Test void rejectsOtherVotingTermPrintAndAmendmentDespiteSimilarTitle() throws Exception {
        assertThatThrownBy(() -> parser.parse(replace("nr 103 na 66.", "nr 40 na 68."), mapping()))
                .isInstanceOf(IOException.class).hasMessageContaining("inne głosowanie");
        assertThatThrownBy(() -> parser.parse(replace("Sejm10.nsf", "Sejm9.nsf"), mapping()))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> parser.parse(replace("2693", "3261"), mapping()))
                .isInstanceOf(IOException.class).hasMessageContaining("druku");
        assertThatThrownBy(() -> parser.parse(replace("całość projektu ustawy", "poprawka nr 1"), mapping()))
                .isInstanceOf(IOException.class).hasMessageContaining("całości");
    }
    @Test void rejectsMissingRowsBadNumbersDuplicateClubsAndBrokenSums() throws Exception {
        assertThatThrownBy(() -> parser.parse(replace("<td>1</td><td>1</td><td>-</td>", "<td>2</td><td>1</td><td>-</td>"), mapping()))
                .isInstanceOf(IOException.class).hasMessageContaining("sumy");
        assertThatThrownBy(() -> parser.parse(replace("<td>KlubTestowyB</td><td>-</td><td>1</td>", "<td>KlubTestowyB</td><td>?</td><td>1</td>"), mapping()))
                .isInstanceOf(IOException.class).hasMessageContaining("komórka");
        assertThatThrownBy(() -> parser.parse(replace("KlubTestowyB", "KlubTestowyA"), mapping()))
                .isInstanceOf(IOException.class).hasMessageContaining("klub");
        assertThatThrownBy(() -> parser.parse(replace("<tr><td>KlubTestowyB</td><td>-</td><td>1</td><td>1</td><td>-</td><td>-</td><td>-</td></tr>", ""), mapping()))
                .isInstanceOf(IOException.class).hasMessageContaining("Tabela");
    }
    @Test void rejectsCaptchaAndTruncatedDocumentOrUnknownColumn() throws Exception {
        assertThatThrownBy(() -> parser.parse(replace("<body>", "<body>captcha"), mapping())).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> parser.parse(replace("</html>", ""), mapping())).isInstanceOf(IOException.class).hasMessageContaining("Niekompletny");
        assertThatThrownBy(() -> parser.parse(replace("Liczba czł.", "Nieznana kolumna"), mapping())).isInstanceOf(IOException.class).hasMessageContaining("nagłówków");
    }
    @Test void optionalFieldsDoNotChangeLegacyConstructorOrOldJson() throws Exception {
        var legacy = VotingFixtures.live();
        var json = VotingFixtures.MAPPER.readTree(VotingFixtures.MAPPER.writeValueAsBytes(legacy));
        assertThat(json.has("clubResults")).isFalse();
        assertThat(VotingFixtures.MAPPER.treeToValue(json, BillVotings.Voting.class).clubResults()).isNull();
    }
}
