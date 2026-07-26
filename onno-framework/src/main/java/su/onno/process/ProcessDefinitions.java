package su.onno.process;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Validated registry of application-provided process definitions. */
public final class ProcessDefinitions {

    private final Map<String, ProcessDefinition<?, ?>> definitions;

    public ProcessDefinitions(List<ProcessDefinition<?, ?>> definitions) {
        Map<String, ProcessDefinition<?, ?>> byKey = new LinkedHashMap<>();
        for (ProcessDefinition<?, ?> definition : definitions) {
            Objects.requireNonNull(definition, "definition");
            String key = Objects.requireNonNull(definition.key(), "definition key").trim();
            if (key.isEmpty()) {
                throw new InvalidProcessDefinitionException("Process definition key must not be blank");
            }
            if (byKey.putIfAbsent(key, definition) != null) {
                throw new InvalidProcessDefinitionException("Duplicate process definition key: " + key);
            }
            definition.graph();
        }
        this.definitions = Map.copyOf(byKey);
    }

    public ProcessDefinition<?, ?> require(String key) {
        ProcessDefinition<?, ?> definition = definitions.get(key);
        if (definition == null) {
            throw new IllegalArgumentException("Unknown process definition: " + key);
        }
        return definition;
    }

    public List<ProcessDefinition<?, ?>> all() {
        return List.copyOf(definitions.values());
    }
}
