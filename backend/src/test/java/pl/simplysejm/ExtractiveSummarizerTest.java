package pl.simplysejm;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ExtractiveSummarizerTest {
    final ExtractiveSummarizer summarizer = new ExtractiveSummarizer();
    final String text = "Projekt ustawy o zmianie zasad publikacji informacji przez organy administracji publicznej. "
            + "W art. 5 dodaje się ustęp zobowiązujący organ do publikowania informacji w terminie siedmiu dni. "
            + "Ustawa wchodzi w życie po upływie 14 dni od dnia ogłoszenia.";
    @Test void isDeterministicAndUsesOnlySourcePassages() {
        var summary = summarizer.summarize(text, false);
        assertThat(summary).isEqualTo(summarizer.summarize(text, false));
        assertThat(text).contains(summary.lead());
        assertThat(summary.keyChanges()).isNotEmpty().allSatisfy(s -> assertThat(text).contains(s));
        assertThat(summary.effectiveDate()).isEqualTo("Ustawa wchodzi w życie po upływie 14 dni od dnia ogłoszenia.");
        assertThat(summary.affectedGroups()).isEmpty();
        assertThat(summary.method()).contains("local-extractive-v1", "bez AI");
    }
    @Test void doesNotInventEffectiveDateOrAffectedGroups() {
        var summary = summarizer.summarize(text.substring(0, text.indexOf("Ustawa wchodzi")), true);
        assertThat(summary.effectiveDate()).contains("Nie ustalono");
        assertThat(summary.affectedGroups()).isEmpty();
        assertThat(summary.caveats()).contains("ograniczoną część");
    }
    @Test void emptyAndScannedPdfRemainPending() {
        assertThat(summarizer.hasText("12345")).isFalse();
        assertThat(summarizer.summarize("", false).keyChanges()).isEmpty();
        assertThat(summarizer.summarize("", false).caveats()).contains("OCR");
    }
    @Test void normalizesPdfLineBreaksWithoutAddingFacts() {
        var summary = summarizer.summarize(text.replace("publikacji", "publi-\nkacji"), false);
        assertThat(summary.lead()).doesNotContain("\n");
        assertThat(summary.lead()).contains("publikacji");
    }
}
