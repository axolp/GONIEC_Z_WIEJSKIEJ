package pl.simplysejm.votings.application;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import pl.simplysejm.bills.api.BillQuery;
import pl.simplysejm.infrastructure.UseCaseException;
import pl.simplysejm.votings.api.BillVotings;
import pl.simplysejm.votings.api.VotingQuery;
import pl.simplysejm.votings.domain.VotingValidation;
import pl.simplysejm.votings.port.*;

@Service
public class VotingService implements VotingQuery {
    private static final Logger log = LoggerFactory.getLogger(VotingService.class);
    private final VotingMappingSource mappings;
    private final VotingRepository cache;
    private final VotingSource client;
    private final BillQuery bills;
    private final Duration refresh;
    private final Duration retry;
    private final Map<String, State> states = new ConcurrentHashMap<>();
    private final Executor worker;
    private final Clock clock;
    private static final class State {
        final String signature;
        final AtomicBoolean refreshing = new AtomicBoolean();
        volatile VotingRepository.Cached cached;
        volatile String error;
        volatile Instant nextAttempt = Instant.MIN;
        State(String signature) { this.signature = signature; }
    }
    public VotingService(VotingMappingSource mappings, VotingRepository cache, VotingSource client, BillQuery bills,
            @Value("${bill-votings.refresh-minutes:30}") long refreshMinutes,
            @Value("${bill-votings.retry-seconds:60}") long retrySeconds, Clock clock,
            @Qualifier("votingExecutor") Executor worker) {
        this.clock = clock; this.worker = worker;
        if (refreshMinutes < 1 || refreshMinutes > 10080 || retrySeconds < 1 || retrySeconds > 3600)
            throw new IllegalArgumentException("Nieprawidłowy interwał odświeżania głosowań");
        this.mappings = mappings; this.cache = cache; this.client = client;
        this.bills = bills;
        refresh = Duration.ofMinutes(refreshMinutes); retry = Duration.ofSeconds(retrySeconds);
    }
    public BillVotings.Response get(String billId) {
        if (!VotingValidation.validBillId(billId)) throw new UseCaseException(UseCaseException.Kind.INVALID_INPUT, "Nieprawidłowy identyfikator ustawy");
        var view = mappings.read();
        var mapping = view.bills().get(billId);
        if (mapping == null) {
            if (view.error() != null) {
                try {
                    var saved = cache.read(billId, null);
                    if (saved.isPresent()) return result(saved.get().response(), "live", true, combine(saved.get().response().error(), view.error()));
                } catch (Exception e) { log.warn("Cannot read voting fallback for {}", billId, e); }
                throw new UseCaseException(UseCaseException.Kind.UNAVAILABLE,
                        "Powiązania głosowań są niedostępne; nie można potwierdzić ich braku");
            }
            if (bills.bill(billId).isEmpty()) throw new UseCaseException(UseCaseException.Kind.NOT_FOUND, "Nie znaleziono projektu ustawy");
            return new BillVotings.Response(billId, null, null, null, List.of(), "snapshot", false, null);
        }
        var snapshot = mapping.snapshot();
        // An explicitly empty verified mapping is not a network failure and needs no refresh.
        if (snapshot.votings().isEmpty()) return result(snapshot, "snapshot", snapshot.stale() || view.error() != null, combine(snapshot.error(), view.error()));
        State state = states.compute(billId, (key, existing) -> {
            if (existing != null && existing.signature.equals(mapping.signature())) return existing;
            var next = new State(mapping.signature());
            try {
                next.cached = cache.read(billId, mapping.signature()).orElse(null);
                if (next.cached != null) {
                    validateMapping(next.cached.response(), mapping.snapshot());
                    for (var voting : next.cached.response().votings())
                        VotingValidation.check(VotingValidation.hasLiveData(voting), "Cache live nie zawiera potwierdzonych wyników");
                }
            }
            catch (Exception e) { next.cached = null; next.error = "Nie można odczytać cache głosowań: " + e.getMessage(); }
            return next;
        });
        Instant now = clock.instant();
        boolean expired = state.cached == null || !state.cached.fetchedAt().plus(refresh).isAfter(now);
        if (expired && !now.isBefore(state.nextAttempt) && state.refreshing.compareAndSet(false, true)) {
            try { worker.execute(() -> refresh(mapping, state)); }
            catch (RejectedExecutionException e) {
                state.refreshing.set(false); state.nextAttempt = now.plus(retry);
                state.error = "Odświeżanie głosowań jest chwilowo niedostępne";
            }
        }
        var cached = state.cached;
        String error = combine(view.error(), state.error);
        if (cached != null) {
            error = combine(cached.response().error(), error);
            return result(cached.response(), "live",
                    cached.response().stale() || !cached.fetchedAt().plus(refresh).isAfter(now) || error != null, error);
        }
        return result(snapshot, "snapshot", true, combine(snapshot.error(), error));
    }
    private void refresh(VotingMappingSource.Mapping mapping, State state) {
        try {
            List<BillVotings.Voting> votings = new ArrayList<>();
            for (var reference : mapping.snapshot().votings()) votings.add(client.fetch(reference));
            var seed = mapping.snapshot();
            var fetchedAt = clock.instant();
            boolean missingRollCall = votings.stream().anyMatch(v -> v.votes().isEmpty());
            var response = new BillVotings.Response(seed.billId(), fetchedAt.toString(), seed.sourceUrl(), seed.defaultVotingId(),
                    List.copyOf(votings), "live", missingRollCall, missingRollCall
                    ? "Brak danych imiennych dla części głosowań; dostępne potwierdzone wyniki klubowe ze strony Sejmu." : null);
            VotingValidation.validate(response);
            validateMapping(response, seed);
            // An old in-flight task must not persist results for a replaced editorial mapping.
            var current = mappings.read().bills().get(seed.billId());
            if (current == null || !current.signature().equals(mapping.signature())) return;
            var saved = new VotingRepository.Cached(1, mapping.signature(), fetchedAt, response);
            cache.save(saved); state.cached = saved; state.error = null;
            state.nextAttempt = saved.fetchedAt().plus(refresh);
        } catch (Exception e) {
            log.warn("Voting refresh failed for {}", mapping.snapshot().billId(), e);
            state.error = "Nie udało się odświeżyć głosowań: " + Objects.toString(e.getMessage(), e.getClass().getSimpleName());
            state.nextAttempt = clock.instant().plus(retry);
        } finally { state.refreshing.set(false); }
    }
    private static void validateMapping(BillVotings.Response actual, BillVotings.Response mapping) throws java.io.IOException {
        VotingValidation.check(actual.billId().equals(mapping.billId())
                && Objects.equals(actual.defaultVotingId(), mapping.defaultVotingId())
                && actual.votings().size() == mapping.votings().size(), "Cache nie odpowiada powiązaniom ustawy");
        for (int i = 0; i < mapping.votings().size(); i++) {
            var expected = mapping.votings().get(i); var found = actual.votings().get(i);
            VotingValidation.check(expected.id().equals(found.id()) && expected.category().equals(found.category()),
                    "Głosowanie lub kategoria nie odpowiada ręcznemu mapowaniu");
        }
    }
    private static BillVotings.Response result(BillVotings.Response source, String mode, boolean stale, String error) {
        return new BillVotings.Response(source.billId(), source.verifiedAt(), source.sourceUrl(), source.defaultVotingId(),
                source.votings(), mode, stale, error);
    }
    private static String combine(String first, String second) {
        return first == null ? second : second == null ? first : first + "; " + second;
    }
}
