package pl.simplysejm.infrastructure.http;

import jakarta.annotation.PreDestroy;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import org.springframework.stereotype.Component;
import pl.simplysejm.bootstrap.SejmProperties;

@Component
public class JdkBoundedHttpTransport implements BoundedHttpTransport {
    private final HttpClient client;
    public JdkBoundedHttpTransport(SejmProperties config) {
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(config.connectTimeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }
    public byte[] fetch(String url, int limit, String accept, Duration deadline) throws IOException {
        var request = HttpRequest.newBuilder(URI.create(url)).timeout(deadline)
                .header("Accept", accept).header("User-Agent", "goniec-z-wiejskiej/1.0").GET().build();
        // A bounded subscriber + future deadline cover the entire body, including a stalled stream.
        var future = client.sendAsync(request, info -> new LimitedSubscriber(limit));
        try {
            var response = future.get(deadline.toMillis(), TimeUnit.MILLISECONDS);
            if (response.statusCode() != 200) throw new IOException("API Sejmu: HTTP " + response.statusCode());
            return response.body();
        } catch (InterruptedException e) {
            future.cancel(true); Thread.currentThread().interrupt(); throw new IOException("Przerwano pobieranie", e);
        } catch (TimeoutException e) {
            future.cancel(true); throw new IOException("Przekroczono czas pobierania z API Sejmu", e);
        } catch (ExecutionException e) {
            future.cancel(true); throw new IOException("Nie udało się pobrać danych z API Sejmu: " + e.getCause().getMessage(), e);
        }
    }
    @PreDestroy public void close() { client.shutdownNow(); }
    private static final class LimitedSubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final int limit;
        private Flow.Subscription subscription;
        LimitedSubscriber(int limit) { this.limit = limit; }
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription s) { subscription = s; s.request(1); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > limit - bytes.size()) {
                    subscription.cancel(); result.completeExceptionally(new IOException("Przekroczono limit pobierania")); return;
                }
                byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable t) { result.completeExceptionally(t); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
