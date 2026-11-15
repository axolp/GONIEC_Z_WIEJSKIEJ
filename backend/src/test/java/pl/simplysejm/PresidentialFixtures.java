package pl.simplysejm;

import java.time.LocalDate;
import java.util.List;
import pl.simplysejm.president.api.PresidentialCommentsQuery.*;

final class PresidentialFixtures {
    static Comment comment(String billId, String source) {
        return new Comment("veto-2693", billId, LocalDate.of(2026, 11, 13),
                "Stanowisko", "Skrót stanowiska", List.of("Argument Prezydenta"), source,
                "https://www.prezydent.pl/storage/file/reason.pdf", "Zweryfikowany skrót");
    }
    static Feed feed(Comment comment) {
        return new Feed("https://www.prezydent.pl/prawo/ustawy-zawetowane",
                LocalDate.of(2026, 11, 15), "snapshot", List.of(comment));
    }
}
