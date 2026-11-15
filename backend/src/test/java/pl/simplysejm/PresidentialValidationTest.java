package pl.simplysejm;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import pl.simplysejm.president.domain.PresidentialValidation;

import static org.assertj.core.api.Assertions.*;
import static pl.simplysejm.PresidentialFixtures.*;

class PresidentialValidationTest {
    @Test void rejectsUntrustedSourcesAndInvalidPrintIds() {
        assertThatThrownBy(() -> PresidentialValidation.validate(feed(comment("10-2693",
                "https://www.prezydent.pl.evil.example/article")))).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> PresidentialValidation.validate(feed(comment("2693",
                "https://www.prezydent.pl/prawo/article")))).isInstanceOf(IOException.class);
    }
    @Test void acceptsVerifiedOfficialSnapshot() throws Exception {
        PresidentialValidation.validate(feed(comment("10-2693", "https://www.prezydent.pl/prawo/article")));
    }
}
