package pl.simplysejm.bootstrap;

import java.time.Clock;
import java.util.concurrent.*;
import org.springframework.context.annotation.*;
import pl.simplysejm.bills.domain.ExtractiveSummarizer;
import pl.simplysejm.bills.domain.PrintSelector;
import pl.simplysejm.votings.domain.VotingHtmlParser;

@Configuration
public class ExecutionConfiguration {
    @Bean public PrintSelector printSelector() { return new PrintSelector(); }
    @Bean public ExtractiveSummarizer summarizer() { return new ExtractiveSummarizer(); }
    @Bean public VotingHtmlParser votingHtmlParser(Clock clock) { return new VotingHtmlParser(clock); }
    @Bean public Clock clock() { return Clock.systemUTC(); }
    @Bean(name = "billExecutor", destroyMethod = "shutdownNow")
    public ExecutorService billExecutor() { return Executors.newSingleThreadExecutor(r -> new Thread(r, "sejm-bills")); }
    @Bean(name = "votingExecutor", destroyMethod = "shutdownNow")
    public ExecutorService votingExecutor() {
        return new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(100),
                r -> new Thread(r, "sejm-votings"), new ThreadPoolExecutor.AbortPolicy());
    }
}
