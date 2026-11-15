package pl.simplysejm;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static pl.simplysejm.VotingFixtures.*;
import static org.mockito.Mockito.*;

class VotingClientTest {
    static final String JSON = """
            {"term":10,"sitting":66,"votingNumber":103,"date":"2026-10-08T20:54:10",
             "title":"Projekt ustawy","description":"Głosowanie nad całością","kind":"ELECTRONIC",
             "yes":1,"no":0,"abstain":0,"notParticipating":null,"totalVoted":1,
             "votes":[{"MP":1,"firstName":"Jan","lastName":"Kowalski","secondName":"Adam","club":"KO","vote":"YES"}]}
            """;
    SejmProperties config(int maxBytes) {
        return new SejmProperties("https://api.sejm.gov.pl/sejm/term10/prints", Path.of("unused"), Path.of("unused"),
                30, false, 1, 1, 1024, maxBytes, 300, 300000, "http://localhost:5173");
    }
    @Test void mapsOfficialRollCallAndRejectsIdentityContradictionsAndOversizedBodies() throws Exception {
        var body = new AtomicReference<>(JSON);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/votings/66/103", exchange -> {
            byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        var transport = spy(new SejmClient(config(4096), MAPPER));
        doThrow(new IOException("HTML disabled in this local API test")).when(transport).votingHtml(anyInt(), anyInt(), anyInt());
        var client = new VotingClient(transport, MAPPER, "http://127.0.0.1:" + server.getAddress().getPort() + "/votings");
        try {
            var result = client.fetch(snapshot().votings().getFirst());
            assertThat(result.notParticipating()).isNull();
            assertThat(result.votes().getFirst().mp()).isEqualTo(1);
            assertThat(result.category()).isEqualTo("final");
            body.set(JSON.replace("\"votingNumber\":103", "\"votingNumber\":104"));
            assertThatThrownBy(() -> client.fetch(live())).isInstanceOf(IOException.class).hasMessageContaining("inne głosowanie");
            body.set(JSON.replace("\"yes\":1", "\"yes\":2"));
            assertThatThrownBy(() -> client.fetch(live())).isInstanceOf(IOException.class).hasMessageContaining("suma");
            body.set(JSON.replace("\"vote\":\"YES\"", "\"vote\":\"NO\""));
            assertThatThrownBy(() -> client.fetch(live())).isInstanceOf(IOException.class).hasMessageContaining("imienny");
            body.set("x".repeat(5000));
            assertThatThrownBy(() -> client.fetch(live())).isInstanceOf(IOException.class).hasMessageContaining("limit");
        } finally { transport.close(); server.stop(0); }
    }
    @Test void deadlineIncludesStalledResponseBody() throws Exception {
        var release = new CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var executor = Executors.newVirtualThreadPerTaskExecutor(); server.setExecutor(executor);
        server.createContext("/votings/66/103", exchange -> {
            exchange.sendResponseHeaders(200, 10);
            try (var out = exchange.getResponseBody()) {
                out.write('{'); out.flush();
                try { release.await(4, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        });
        server.start();
        var transport = spy(new SejmClient(config(4096), MAPPER));
        doThrow(new IOException("HTML disabled in this local API test")).when(transport).votingHtml(anyInt(), anyInt(), anyInt());
        try {
            var client = new VotingClient(transport, MAPPER, "http://127.0.0.1:" + server.getAddress().getPort() + "/votings");
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(3), () ->
                    assertThatThrownBy(() -> client.fetch(live())).isInstanceOf(IOException.class));
        } finally { release.countDown(); transport.close(); server.stop(0); executor.shutdownNow(); }
    }
}
