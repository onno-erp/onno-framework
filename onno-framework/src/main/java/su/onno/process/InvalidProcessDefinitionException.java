package su.onno.process;

/** Raised when a typed process graph is structurally incomplete or inconsistent. */
public class InvalidProcessDefinitionException extends IllegalStateException {

    public InvalidProcessDefinitionException(String message) {
        super(message);
    }
}
