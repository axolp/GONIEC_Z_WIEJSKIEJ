package pl.simplysejm;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import pl.simplysejm.infrastructure.UseCaseException;
import pl.simplysejm.votings.adapter.web.VotingController;
import pl.simplysejm.votings.application.VotingService;
import pl.simplysejm.web.ApiErrors;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static pl.simplysejm.VotingFixtures.*;

class VotingControllerIT {
    @Test void serializesContractIncludingNullFieldsAndReturnsProblemDetail() throws Exception {
        var service = mock(VotingService.class);
        when(service.get("10-2693")).thenReturn(snapshot());
        when(service.get("10-99999")).thenThrow(new UseCaseException(UseCaseException.Kind.NOT_FOUND, "Nie znaleziono projektu ustawy"));
        when(service.get("10-3355")).thenThrow(new UseCaseException(UseCaseException.Kind.UNAVAILABLE, "Powiązania niedostępne"));
        when(service.get("10-500")).thenThrow(new IllegalStateException("internal diagnostic"));
        var mvc = MockMvcBuilders.standaloneSetup(new VotingController(service)).setControllerAdvice(new ApiErrors()).build();
        mvc.perform(get("/api/bills/10-2693/votings")).andExpect(status().isOk())
                .andExpect(jsonPath("$.billId").value("10-2693"))
                .andExpect(jsonPath("$.defaultVotingId").value("10-66-103"))
                .andExpect(jsonPath("$.mode").value("snapshot"))
                .andExpect(jsonPath("$.votings[0].votes").isEmpty())
                .andExpect(jsonPath("$.votings[0].notParticipating").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.nullValue()));
        mvc.perform(get("/api/bills/10-99999/votings")).andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json")).andExpect(jsonPath("$.detail").exists());
        mvc.perform(get("/api/bills/10-3355/votings")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("Powiązania niedostępne"));
        mvc.perform(get("/api/bills/10-500/votings")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("Wewnętrzny błąd serwera"));
    }
}
