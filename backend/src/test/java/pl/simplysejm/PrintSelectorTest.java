package pl.simplysejm;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class PrintSelectorTest {
    final PrintSelector selector = new PrintSelector();
    PrintDocument print(String n, String date, String title) {
        return new PrintDocument(n, 10, title, LocalDate.parse(date), null, List.of(n + ".pdf"));
    }
    @Test void excludesResolutionsReportsAndAdditionalPrints() {
        var bill = print("3355", "2026-11-13", "Rządowy projekt ustawy o energetyce jądrowej");
        assertThat(selector.latest(List.of(bill,
                print("3356", "2026-11-14", "Poselski projekt uchwały w sprawie rocznicy"),
                print("3357", "2026-11-14", "Sprawozdanie Komisji o rządowym projekcie ustawy"),
                print("3355-001", "2026-11-14", "Rządowy projekt ustawy o zmianie ustawy"))))
                .contains(bill);
    }
    @Test void ordersByDeliveryDateThenNumericNumberNotLexicographically() {
        var expected = print("100", "2026-11-13", "Poselski projekt ustawy o zmianie ustawy");
        assertThat(selector.latest(List.of(expected,
                print("99", "2026-11-13", "Senacki projekt ustawy o podatkach"),
                print("9999", "2026-11-12", "Obywatelski projekt ustawy o podatkach"))))
                .contains(expected);
    }
    @Test void handlesWhitespaceCaseAndPresidentialBill() {
        assertThat(selector.isBill(print("20", "2026-01-01", "  Przedstawiony przez Prezydenta Rzeczypospolitej Polskiej projekt   ustawy o zmianie ustawy"))).isTrue();
        assertThat(selector.isBill(print("21", "2026-01-01", "KOMISYJNY PROJEKT USTAWY o podatkach"))).isTrue();
        assertThat(selector.latest(List.of())).isEmpty();
        assertThat(selector.isBill(new PrintDocument("22", 10, "Rządowy projekt ustawy", null, null, null))).isFalse();
    }
}
