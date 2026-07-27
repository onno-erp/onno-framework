package su.onno.process;

import java.util.List;
import java.util.Objects;

/** Immutable structural description of a validated process graph. */
public record ProcessGraphDescriptor(
        String startStepKey,
        List<ProcessNodeDescriptor> nodes) {

    public ProcessGraphDescriptor {
        startStepKey = Objects.requireNonNull(startStepKey, "startStepKey");
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
    }
}
