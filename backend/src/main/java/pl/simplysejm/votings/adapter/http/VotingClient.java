package pl.simplysejm.votings.adapter.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import pl.simplysejm.bootstrap.SejmProperties;
import pl.simplysejm.infrastructure.http.BoundedHttpTransport;
import pl.simplysejm.votings.api.BillVotings;
import pl.simplysejm.votings.domain.VotingHtmlParser;
import pl.simplysejm.votings.domain.VotingValidation;
import pl.simplysejm.votings.port.VotingSource;

@Component
public class VotingClient implements VotingSource {
    private final BoundedHttpTransport transport;
    private final ObjectMapper mapper;
    private final String baseUrl;
    private final VotingHtmlParser htmlParser;
    private final SejmProperties config;
    /* production constructor */
    @Autowired
    public VotingClient(BoundedHttpTransport transport, ObjectMapper mapper,
            @Value("${bill-votings.base-url:https://api.sejm.gov.pl/sejm/term10/votings}") String baseUrl,
            VotingHtmlParser htmlParser, SejmProperties config) {
        this.transport = transport; this.mapper = mapper; this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.htmlParser = htmlParser; this.config = config;
    }
    public BillVotings.Voting fetch(BillVotings.Voting mapping) throws IOException {
        try { return fetchApi(mapping); }
        catch (IOException | IllegalArgumentException apiFailure) {
            try { return htmlParser.parse(transport.fetch(VotingHtmlParser.sourceUrl(mapping.term(), mapping.sitting(), mapping.votingNumber()),
                    Math.min(config.maxJsonBytes(), 2 * 1024 * 1024), "text/html", Duration.ofSeconds(config.requestTimeoutSeconds())), mapping); }
            catch (IOException | IllegalArgumentException htmlFailure) {
                IOException failure = new IOException("API: " + apiFailure.getMessage() + "; HTML: " + htmlFailure.getMessage(), apiFailure);
                failure.addSuppressed(htmlFailure); throw failure;
            }
        }
    }
    private BillVotings.Voting fetchApi(BillVotings.Voting mapping) throws IOException {
        JsonNode json = mapper.readTree(transport.fetch(baseUrl + "/" + mapping.sitting() + "/" + mapping.votingNumber(),
                config.maxJsonBytes(), "application/json", Duration.ofSeconds(config.requestTimeoutSeconds())));
        VotingValidation.check(json != null && json.isObject(), "Nieprawidłowy JSON głosowania");
        VotingValidation.check(integer(json, "term") == mapping.term() && integer(json, "sitting") == mapping.sitting()
                && integer(json, "votingNumber") == mapping.votingNumber(), "API zwróciło inne głosowanie");
        VotingValidation.check(json.has("votes") && json.get("votes").isArray() && !json.get("votes").isEmpty(), "API nie zwróciło danych imiennych");
        List<BillVotings.Vote> votes = new ArrayList<>();
        for (JsonNode node : json.get("votes")) votes.add(mapper.treeToValue(node, BillVotings.Vote.class));
        String url = "https://api.sejm.gov.pl/sejm/term10/votings/" + mapping.sitting() + "/" + mapping.votingNumber();
        var result = new BillVotings.Voting(mapping.id(), mapping.term(), mapping.sitting(), mapping.votingNumber(),
                text(json, "date", null), text(json, "title", null), text(json, "description", ""), mapping.category(),
                url, url + "/pdf", count(json, "yes"), count(json, "no"), count(json, "abstain"),
                count(json, "notParticipating"), count(json, "totalVoted"), List.copyOf(votes));
        VotingValidation.validate(result);
        return result;
    }
    private int integer(JsonNode json, String key) throws IOException {
        Integer value = count(json, key);
        if (value == null) throw new IOException("Brak pola " + key + " w API głosowań");
        return value;
    }
    private Integer count(JsonNode json, String key) throws IOException {
        JsonNode n = json.get(key);
        if (n == null || n.isNull()) return null;
        if (!n.isIntegralNumber() || !n.canConvertToInt()) throw new IOException("Nieprawidłowe pole " + key);
        return n.intValue();
    }
    private String text(JsonNode json, String key, String fallback) throws IOException {
        JsonNode n = json.get(key);
        if (n == null || n.isNull()) return fallback;
        if (!n.isTextual()) throw new IOException("Nieprawidłowe pole " + key);
        return n.textValue();
    }
}
