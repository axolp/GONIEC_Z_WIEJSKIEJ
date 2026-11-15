package pl.simplysejm;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import pl.simplysejm.bootstrap.SejmProperties;
import pl.simplysejm.infrastructure.http.JdkBoundedHttpTransport;
import pl.simplysejm.votings.adapter.http.VotingClient;
import pl.simplysejm.votings.domain.VotingHtmlParser;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static pl.simplysejm.VotingFixtures.*;

class VotingClientIT {
    SejmProperties config(int maxBytes) {
        return new SejmProperties("https://api.sejm.gov.pl/sejm/term10/prints", Path.of("unused"), Path.of("unused"),
                30, false, 1, 1, 1024, maxBytes, 300, 300000, "http://localhost:5173");
    }
    @Test void mapsOfficialRollCallAndRejectsIdentityContradictionsAndOversizedBodies() throws Exception {
        var body = new AtomicReference<>(VOTING_JSON);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/votings/66/103", exchange -> {
            byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        var transport = spy(new JdkBoundedHttpTransport(config(4096)));
        doThrow(new IOException("HTML disabled in this local API test")).when(transport).fetch(anyString(), anyInt(), eq("text/html"), any());
        var client = new VotingClient(transport, MAPPER, "http://127.0.0.1:" + server.getAddress().getPort() + "/votings", new VotingHtmlParser(java.time.Clock.systemUTC()), config(4096));
        try {
            var result = client.fetch(snapshot().votings().getFirst());
            assertThat(result.notParticipating()).isNull();
            assertThat(result.votes().getFirst().mp()).isEqualTo(1);
            assertThat(result.category()).isEqualTo("final");
            body.set(VOTING_JSON.replace("\"votingNumber\":103", "\"votingNumber\":104"));
            assertThatThrownBy(() -> client.fetch(live())).isInstanceOf(IOException.class).hasMessageContaining("inne głosowanie");
            body.set(VOTING_JSON.replace("\"yes\":1", "\"yes\":2"));
            assertThatThrownBy(() -> client.fetch(live())).isInstanceOf(IOException.class).hasMessageContaining("suma");
            body.set(VOTING_JSON.replace("\"vote\":\"YES\"", "\"vote\":\"NO\""));
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
        var transport = spy(new JdkBoundedHttpTransport(config(4096)));
        doThrow(new IOException("HTML disabled in this local API test")).when(transport).fetch(anyString(), anyInt(), eq("text/html"), any());
        try {
            var client = new VotingClient(transport, MAPPER, "http://127.0.0.1:" + server.getAddress().getPort() + "/votings", new VotingHtmlParser(java.time.Clock.systemUTC()), config(4096));
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(3), () ->
                    assertThatThrownBy(() -> client.fetch(live())).isInstanceOf(IOException.class));
        } finally { release.countDown(); transport.close(); server.stop(0); executor.shutdownNow(); }
    }
}
