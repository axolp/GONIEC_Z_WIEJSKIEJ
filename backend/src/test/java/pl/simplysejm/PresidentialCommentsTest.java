package pl.simplysejm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class PresidentialCommentsTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private PresidentialComments.Comment comment(String billId, String source) {
        return new PresidentialComments.Comment("veto-2693", billId, LocalDate.of(2026, 11, 13),
                "Stanowisko", "Skrót stanowiska", List.of("Argument Prezydenta"), source,
                "https://www.prezydent.pl/storage/file/reason.pdf", "Zweryfikowany skrót");
    }
    private PresidentialComments.Feed feed(PresidentialComments.Comment comment) {
        return new PresidentialComments.Feed("https://www.prezydent.pl/prawo/ustawy-zawetowane",
                LocalDate.of(2026, 11, 15), "snapshot", List.of(comment));
    }
    @Test void readsSnapshotIndependentlyOfSejmCache() throws Exception {
        Path file = directory.resolve("comments.json");
        var expected = feed(comment("10-2693", "https://www.prezydent.pl/prawo/article,129977"));
        mapper.writeValue(file.toFile(), expected);
        assertThat(new PresidentialComments(mapper, file.toString()).comments()).isEqualTo(expected);
    }
    @Test void rejectsUntrustedSourcesAndInvalidPrintIds() {
        assertThatThrownBy(() -> PresidentialComments.validate(feed(comment("10-2693",
                "https://www.prezydent.pl.evil.example/article")))).isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(() -> PresidentialComments.validate(feed(comment("2693",
                "https://www.prezydent.pl/prawo/article")))).isInstanceOf(java.io.IOException.class);
    }
    @Test void missingOrCorruptSnapshotDoesNotReturnAnEmptySuccess() throws Exception {
        Path file = directory.resolve("missing.json");
        var controller = new PresidentialComments(mapper, file.toString());
        assertThatThrownBy(controller::comments).isInstanceOf(ResponseStatusException.class);
        Files.writeString(file, "{broken");
        assertThatThrownBy(controller::comments).isInstanceOf(ResponseStatusException.class);
    }
}
