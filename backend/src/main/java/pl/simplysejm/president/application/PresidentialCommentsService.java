package pl.simplysejm.president.application;

import java.io.IOException;
import org.springframework.stereotype.Service;
import pl.simplysejm.infrastructure.UseCaseException;
import pl.simplysejm.president.api.PresidentialCommentsQuery;
import pl.simplysejm.president.port.PresidentialSnapshotSource;

@Service
public class PresidentialCommentsService implements PresidentialCommentsQuery {
    private final PresidentialSnapshotSource source;
    public PresidentialCommentsService(PresidentialSnapshotSource source) { this.source = source; }
    public Feed comments() {
        try { return source.read(); }
        catch (IOException | IllegalArgumentException e) {
            throw new UseCaseException(UseCaseException.Kind.UNAVAILABLE, "Stanowiska Prezydenta są chwilowo niedostępne", e);
        }
    }
}
