package pl.simplysejm;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pl.simplysejm.infrastructure.UseCaseException;
import pl.simplysejm.president.adapter.file.PresidentialComments;
import pl.simplysejm.president.application.PresidentialCommentsService;
import pl.simplysejm.president.domain.PresidentialValidation;

import static org.assertj.core.api.Assertions.*;
import static pl.simplysejm.PresidentialFixtures.*;

class PresidentialCommentsIT {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    @Test void readsSnapshotIndependentlyOfSejmCache() throws Exception {
        Path file = directory.resolve("comments.json");
        var expected = feed(comment("10-2693", "https://www.prezydent.pl/prawo/article,129977"));
        mapper.writeValue(file.toFile(), expected);
        assertThat(new PresidentialComments(mapper, file.toString()).read()).isEqualTo(expected);
    }
    @Test void rejectsUntrustedSourcesAndInvalidPrintIds() {
        assertThatThrownBy(() -> PresidentialValidation.validate(feed(comment("10-2693",
                "https://www.prezydent.pl.evil.example/article")))).isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(() -> PresidentialValidation.validate(feed(comment("2693",
                "https://www.prezydent.pl/prawo/article")))).isInstanceOf(java.io.IOException.class);
    }
    @Test void missingOrCorruptSnapshotDoesNotReturnAnEmptySuccess() throws Exception {
        Path file = directory.resolve("missing.json");
        var controller = new PresidentialCommentsService(new PresidentialComments(mapper, file.toString()));
        assertThatThrownBy(controller::comments).isInstanceOf(UseCaseException.class);
        Files.writeString(file, "{broken");
        assertThatThrownBy(controller::comments).isInstanceOf(UseCaseException.class);
    }
}
