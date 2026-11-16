package su.onno.ui.period;

import su.onno.period.PeriodClosedException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps a {@link PeriodClosedException} — a write, post, unpost or posting preview inside a closed
 * period — to HTTP 409 with the closed scope and date, so clients show the remediation message
 * instead of a 500. Applies application-wide, covering app-written controllers too.
 */
@RestControllerAdvice
public class PeriodClosedExceptionHandler {

    @ExceptionHandler(PeriodClosedException.class)
    public ResponseEntity<Map<String, Object>> handle(PeriodClosedException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "period-closed");
        body.put("message", ex.getMessage());
        body.put("scope", ex.lock().scope().toString());
        body.put("closedThrough", ex.lock().closedThrough().toString());
        body.put("operation", ex.operation().name());
        if (ex.documentDate() != null) {
            body.put("documentDate", ex.documentDate().toString());
        }
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }
}
