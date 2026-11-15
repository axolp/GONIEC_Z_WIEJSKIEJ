package pl.simplysejm.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import pl.simplysejm.infrastructure.UseCaseException;

@RestControllerAdvice
public class ApiErrors extends ResponseEntityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiErrors.class);
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ProblemDetail> status(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(ProblemDetail.forStatusAndDetail(
                error.getStatusCode(), error.getReason() == null ? "Błąd żądania" : error.getReason()));
    }
    @ExceptionHandler(UseCaseException.class)
    public ResponseEntity<ProblemDetail> useCase(UseCaseException error) {
        HttpStatus status = switch (error.kind()) {
            case INVALID_INPUT -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, error.getMessage()));
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> unexpected(Exception error) {
        log.error("API request failed", error);
        return ResponseEntity.internalServerError().body(ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "Wewnętrzny błąd serwera"));
    }
}
