package pl.simplysejm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

@Component
public class SejmClient {
    private final SejmProperties config;
    private final ObjectMapper mapper;
    private final HttpClient client;
    public SejmClient(SejmProperties config, ObjectMapper mapper) {
        this.config = config; this.mapper = mapper;
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(config.connectTimeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }
    public List<PrintDocument> prints() throws IOException {
        return mapper.readValue(fetch(config.baseUrl(), config.maxJsonBytes(), "application/json"), new TypeReference<>() {});
    }
    // Shared bounded transport; voting downloads inherit body limits and full-response deadlines.
    byte[] json(String url) throws IOException {
        return fetch(url, config.maxJsonBytes(), "application/json");
    }
    byte[] votingHtml(int term, int sitting, int number) throws IOException {
        // No client-supplied URL or redirect may change this allowlisted destination.
        return fetch(VotingHtmlParser.sourceUrl(term, sitting, number),
                Math.min(config.maxJsonBytes(), 2 * 1024 * 1024), "text/html");
    }
    public PrintDocument detail(String number) throws IOException {
        requireNumber(number);
        return mapper.readValue(fetch(config.baseUrl() + "/" + number, config.maxJsonBytes(), "application/json"), PrintDocument.class);
    }
    public String attachment(PrintDocument print) throws IOException {
        if (print.attachments() == null) throw new IOException("Druk nie ma załączników PDF");
        return print.attachments().stream().filter(s -> s != null && s.matches("[A-Za-z0-9_.-]+(?i:\\.pdf)") && !s.contains(".."))
                .min(Comparator.comparing((String s) -> !s.equalsIgnoreCase(print.number() + ".pdf")).thenComparing(s -> s))
                .orElseThrow(() -> new IOException("Druk nie ma bezpiecznej nazwy załącznika PDF"));
    }
    public String pdfUrl(PrintDocument print, String attachment) {
        requireNumber(print.number());
        return config.baseUrl() + "/" + print.number() + "/" + attachment;
    }
    public byte[] pdf(PrintDocument print, String attachment) throws IOException {
        byte[] bytes = fetch(pdfUrl(print, attachment), config.maxPdfBytes(), "application/pdf");
        if (bytes.length < 5 || !new String(bytes, 0, 5, java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-"))
            throw new IOException("Załącznik nie jest plikiem PDF");
        return bytes;
    }
    private static void requireNumber(String number) {
        if (number == null || !number.matches("[0-9]+")) throw new IllegalArgumentException("Nieprawidłowy numer druku");
    }
    private byte[] fetch(String url, int limit, String accept) throws IOException {
        var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(config.requestTimeoutSeconds()))
                .header("Accept", accept).header("User-Agent", "goniec-z-wiejskiej/1.0").GET().build();
        // A bounded subscriber + future deadline cover the entire body, including a stalled stream.
        var future = client.sendAsync(request, info -> new LimitedSubscriber(limit));
        try {
            var response = future.get(config.requestTimeoutSeconds(), TimeUnit.SECONDS);
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
    @PreDestroy void close() { client.shutdownNow(); }
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
