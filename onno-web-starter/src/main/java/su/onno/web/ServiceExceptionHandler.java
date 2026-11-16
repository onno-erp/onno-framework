package su.onno.web;

import su.onno.service.ServiceException;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.IOException;

/**
 * Answers a {@link ServiceException} from the generic services with its HTTP status. It goes through
 * {@code sendError}, exactly as Spring answers a {@code ResponseStatusException}, so the error body
 * (Boot's {@code /error} JSON) is the same one clients already read.
 */
@RestControllerAdvice
public class ServiceExceptionHandler {

    @ExceptionHandler(ServiceException.class)
    public void handle(ServiceException ex, HttpServletResponse response) throws IOException {
        if (ex.getMessage() == null) {
            response.sendError(ex.status().code());
        } else {
            response.sendError(ex.status().code(), ex.getMessage());
        }
    }
}
