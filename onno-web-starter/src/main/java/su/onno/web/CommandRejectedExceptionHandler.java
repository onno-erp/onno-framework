package su.onno.web;

import su.onno.command.CommandFeedback;
import su.onno.command.CommandRejectedException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps expected command business-rule rejections to the typed HTTP 422 wire contract. */
@RestControllerAdvice
public class CommandRejectedExceptionHandler {

    @ExceptionHandler(CommandRejectedException.class)
    public ResponseEntity<CommandFeedback> handle(CommandRejectedException exception) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(exception.feedback());
    }
}
