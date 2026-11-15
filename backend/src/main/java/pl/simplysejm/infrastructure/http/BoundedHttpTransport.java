package pl.simplysejm.infrastructure.http;

import java.io.IOException;
import java.time.Duration;

public interface BoundedHttpTransport {
    byte[] fetch(String url, int limit, String accept, Duration deadline) throws IOException;
}
