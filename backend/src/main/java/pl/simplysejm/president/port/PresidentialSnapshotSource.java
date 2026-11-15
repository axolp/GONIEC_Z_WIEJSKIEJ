package pl.simplysejm.president.port;

import java.io.IOException;
import pl.simplysejm.president.api.PresidentialCommentsQuery.Feed;

public interface PresidentialSnapshotSource { Feed read() throws IOException; }
