package su.onno.ui;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps expected action business-rule rejections to the typed HTTP 422 wire contract. */
@RestControllerAdvice
public class ActionRejectedExceptionHandler {

    @ExceptionHandler(ActionRejectedException.class)
    public ResponseEntity<ActionFeedback> handle(ActionRejectedException exception) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(exception.feedback());
    }
}
