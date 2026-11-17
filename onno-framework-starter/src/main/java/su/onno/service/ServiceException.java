package su.onno.service;

/**
 * A request the generic query/command services refused: unknown entity or record, a caller without
 * access, malformed input, a conflicting state. The services are transport-neutral — REST, MCP, CSV
 * import and application code all call them — so they report the refusal as a {@link Status} rather
 * than an HTTP response. The web starter maps it onto the matching HTTP status; other callers read
 * {@link #status()} and {@link #getMessage()} directly.
 */
public class ServiceException extends RuntimeException {

    /** Why the request was refused. Each kind carries the HTTP status the web layer answers with. */
    public enum Status {
        BAD_REQUEST(400),
        UNAUTHORIZED(401),
        FORBIDDEN(403),
        NOT_FOUND(404),
        CONFLICT(409),
        UNPROCESSABLE_ENTITY(422),
        INTERNAL_SERVER_ERROR(500);

        private final int code;

        Status(int code) {
            this.code = code;
        }

        /** The HTTP status code for this refusal. */
        public int code() {
            return code;
        }
    }

    private final Status status;

    public ServiceException(Status status) {
        this(status, null, null);
    }

    public ServiceException(Status status, String message) {
        this(status, message, null);
    }

    public ServiceException(Status status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    /** Why the request was refused. */
    public Status status() {
        return status;
    }

    /** The refusal's message, or {@code null} when it has none (the status alone explains it). */
    public String getReason() {
        return getMessage();
    }
}
