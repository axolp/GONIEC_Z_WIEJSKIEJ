package pl.simplysejm;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import pl.simplysejm.bills.api.Bill;
import pl.simplysejm.votings.api.BillVotings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static pl.simplysejm.VotingFixtures.*;

/** Owns application lifetimes directly; no Spring TestContext cache or listeners. */
class BackendLifecycleIT {
    @TempDir Path directory;

    @Test void terminatesFirstApplicationAndRestoresLiveVotingCacheWithoutSource() throws Exception {
        MAPPER.writeValue(directory.resolve("seed.json").toFile(), new Bill.Bills(List.of()));
        MAPPER.writeValue(directory.resolve("mapping.json").toFile(), new BillVotings.File(List.of(snapshot())));
        var requests = new AtomicInteger();
        var source = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        source.createContext("/votings/66/103", exchange -> {
            requests.incrementAndGet();
            byte[] bytes = VOTING_JSON.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        source.start();
        boolean sourceStopped = false;
        String baseUrl = "http://127.0.0.1:" + source.getAddress().getPort();
        try {
            var first = startApplication(baseUrl);
            ExecutorService billExecutor;
            ExecutorService votingExecutor;
            try (first) {
                billExecutor = first.getBean("billExecutor", ExecutorService.class);
                votingExecutor = first.getBean("votingExecutor", ExecutorService.class);
                String url = votingUrl(first);
                var http = new TestRestTemplate();
                await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertLive(http.getForObject(url, JsonNode.class)));
                assertThat(Files.isRegularFile(directory.resolve("cache/votings/10-2693.json"))).isTrue();
                assertThat(requests.get()).isEqualTo(1);
                source.stop(0);
                sourceStopped = true;
            }
            assertThat(first.isActive()).isFalse();
            assertThat(billExecutor.isShutdown()).isTrue();
            assertThat(votingExecutor.isShutdown()).isTrue();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                assertThat(billExecutor.isTerminated()).isTrue();
                assertThat(votingExecutor.isTerminated()).isTrue();
            });
            int callsBeforeRestart = requests.get();
            try (var second = startApplication(baseUrl)) {
                assertLive(new TestRestTemplate().getForObject(votingUrl(second), JsonNode.class));
                assertThat(requests.get()).isEqualTo(callsBeforeRestart);
            }
        } finally {
            if (!sourceStopped) source.stop(0);
        }
    }

    private ConfigurableApplicationContext startApplication(String baseUrl) {
        return new SpringApplicationBuilder(Application.class).run(
                "--server.port=0", "--sejm.startup-sync=false", "--sejm.scheduling-enabled=false",
                "--sejm.base-url=" + baseUrl + "/prints", "--bill-votings.base-url=" + baseUrl + "/votings",
                "--sejm.data-dir=" + directory.resolve("cache"), "--sejm.seed-path=" + directory.resolve("seed.json"),
                "--bill-votings.path=" + directory.resolve("mapping.json"),
                "--president.comments-path=" + directory.resolve("president.json"));
    }

    private String votingUrl(ConfigurableApplicationContext context) {
        int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
        return "http://127.0.0.1:" + port + "/api/bills/10-2693/votings";
    }

    private void assertLive(JsonNode response) {
        assertThat(response).isNotNull();
        assertThat(response.path("mode").asText()).isEqualTo("live");
        assertThat(response.path("stale").asBoolean()).isFalse();
        assertThat(response.path("error").isNull()).isTrue();
        var votes = response.path("votings").get(0).path("votes");
        assertThat(votes.size()).isEqualTo(1);
        assertThat(votes.get(0).path("MP").asInt()).isEqualTo(1);
    }
}
