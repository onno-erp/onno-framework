package su.onno.process;

import java.util.Map;
import java.util.Objects;

/** Inspectable node identity, kind, and named outgoing routes. */
public record ProcessNodeDescriptor(
        String stepKey,
        ProcessNodeType type,
        Map<String, String> routes) {

    public ProcessNodeDescriptor {
        stepKey = Objects.requireNonNull(stepKey, "stepKey");
        type = Objects.requireNonNull(type, "type");
        routes = routes == null ? Map.of() : Map.copyOf(routes);
    }
}
