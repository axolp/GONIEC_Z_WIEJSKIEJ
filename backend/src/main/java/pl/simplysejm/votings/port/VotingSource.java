package pl.simplysejm.votings.port;

import java.io.IOException;
import pl.simplysejm.votings.api.BillVotings;

public interface VotingSource { BillVotings.Voting fetch(BillVotings.Voting mapping) throws IOException; }
