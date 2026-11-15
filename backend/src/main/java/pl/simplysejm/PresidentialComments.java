package pl.simplysejm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;

/** Verified editorial snapshots, independent of Sejm synchronization and analysis status. */
@RestController
public class PresidentialComments {
    public record Comment(String id, String billId, LocalDate date, String title, String summary,
                          List<String> arguments, String sourceUrl, String documentUrl, String method) {}
    public record Feed(String sourceUrl, LocalDate verifiedAt, String mode, List<Comment> comments) {}
    private final ObjectMapper mapper;
    private final Path path;

    public PresidentialComments(ObjectMapper mapper,
            @Value("${president.comments-path:../data/presidential-comments.json}") String path) {
        this.mapper = mapper;
        this.path = Path.of(path);
    }

    @GetMapping("/api/presidential-comments")
    public Feed comments() {
        try {
            if (Files.size(path) > 1_048_576) throw new IOException("Plik jest zbyt duży");
            Feed feed = mapper.readValue(path.toFile(), Feed.class);
            validate(feed);
            return feed;
        } catch (IOException | IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Stanowiska Prezydenta są chwilowo niedostępne", e);
        }
    }

    static void validate(Feed feed) throws IOException {
        if (feed == null || feed.verifiedAt() == null || !"snapshot".equals(feed.mode())
                || feed.comments() == null || !officialUrl(feed.sourceUrl()))
            throw new IOException("Nieprawidłowa migawka stanowisk Prezydenta");
        Set<String> ids = new HashSet<>();
        for (Comment c : feed.comments()) {
            if (c == null || blank(c.id()) || !ids.add(c.id()) || c.date() == null || blank(c.title())
                    || blank(c.summary()) || c.arguments() == null || c.arguments().stream().anyMatch(PresidentialComments::blank)
                    || blank(c.method()) || !officialUrl(c.sourceUrl()) || !officialUrl(c.documentUrl())
                    || (c.billId() != null && !c.billId().matches("[1-9][0-9]*-[0-9]+")))
                throw new IOException("Nieprawidłowy komentarz Prezydenta");
        }
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
    private static boolean officialUrl(String value) {
        try {
            URI uri = URI.create(value == null ? "" : value);
            return "https".equals(uri.getScheme()) && "www.prezydent.pl".equals(uri.getHost())
                    && uri.getUserInfo() == null && uri.getPort() == -1;
        } catch (IllegalArgumentException e) { return false; }
    }
}
