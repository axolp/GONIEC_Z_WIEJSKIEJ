package pl.simplysejm.president.adapter.file;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import pl.simplysejm.president.api.PresidentialCommentsQuery;
import pl.simplysejm.president.domain.PresidentialValidation;
import pl.simplysejm.president.port.PresidentialSnapshotSource;

/** Verified editorial snapshots, independent of Sejm synchronization and analysis status. */
@org.springframework.stereotype.Component
public class PresidentialComments implements PresidentialSnapshotSource {
    private final ObjectMapper mapper;
    private final Path path;

    public PresidentialComments(ObjectMapper mapper,
            @Value("${president.comments-path:../data/presidential-comments.json}") String path) {
        this.mapper = mapper;
        this.path = Path.of(path);
    }

    public PresidentialCommentsQuery.Feed read() throws IOException {
            if (Files.size(path) > 1_048_576) throw new IOException("Plik jest zbyt duży");
            var feed = mapper.readValue(path.toFile(), PresidentialCommentsQuery.Feed.class);
            PresidentialValidation.validate(feed);
            return feed;
    }
}
