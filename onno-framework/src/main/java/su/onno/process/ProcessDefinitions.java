package su.onno.process;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;

/** Validated, version-aware registry of application-provided process definitions. */
public final class ProcessDefinitions {

    private final Map<String, NavigableMap<Integer, ProcessDefinition<?, ?>>> definitions;
    private final Map<DefinitionVersion, ProcessDefinitionMigration<?, ?, ?, ?>> migrations;

    /** Register definitions without version migrations. Existing definitions default to version 1. */
    public ProcessDefinitions(List<ProcessDefinition<?, ?>> definitions) {
        this(definitions, List.of());
    }

    /** Register versioned definitions and their deterministic forward migration edges. */
    public ProcessDefinitions(
            List<ProcessDefinition<?, ?>> definitions,
            List<ProcessDefinitionMigration<?, ?, ?, ?>> migrations) {
        Map<String, NavigableMap<Integer, ProcessDefinition<?, ?>>> byKey = new LinkedHashMap<>();
        for (ProcessDefinition<?, ?> definition
                : Objects.requireNonNull(definitions, "definitions")) {
            Objects.requireNonNull(definition, "definition");
            String key = Objects.requireNonNull(definition.key(), "definition key").trim();
            if (key.isEmpty()) {
                throw new InvalidProcessDefinitionException(
                        "Process definition key must not be blank");
            }
            NavigableMap<Integer, ProcessDefinition<?, ?>> versions =
                    byKey.computeIfAbsent(key, ignored -> new TreeMap<>());
            if (versions.putIfAbsent(definition.version(), definition) != null) {
                throw new InvalidProcessDefinitionException(
                        "Duplicate process definition key and version: "
                                + key + " v" + definition.version());
            }
            definition.graph();
        }

        Map<DefinitionVersion, ProcessDefinitionMigration<?, ?, ?, ?>> bySource =
                new LinkedHashMap<>();
        for (ProcessDefinitionMigration<?, ?, ?, ?> migration
                : Objects.requireNonNull(migrations, "migrations")) {
            validateAndAddMigration(byKey, bySource, migration);
        }

        Map<String, NavigableMap<Integer, ProcessDefinition<?, ?>>> immutable =
                new LinkedHashMap<>();
        byKey.forEach((key, versions) ->
                immutable.put(key, java.util.Collections.unmodifiableNavigableMap(
                        new TreeMap<>(versions))));
        this.definitions = java.util.Collections.unmodifiableMap(
                new LinkedHashMap<>(immutable));
        this.migrations = java.util.Collections.unmodifiableMap(
                new LinkedHashMap<>(bySource));
    }

    /** Resolve the latest registered version for a stable definition key. */
    public ProcessDefinition<?, ?> require(String key) {
        NavigableMap<Integer, ProcessDefinition<?, ?>> versions = definitions.get(key);
        if (versions == null) {
            throw new IllegalArgumentException("Unknown process definition: " + key);
        }
        return versions.lastEntry().getValue();
    }

    /** Resolve the exact definition version persisted by an instance. */
    public ProcessDefinition<?, ?> require(String key, int version) {
        NavigableMap<Integer, ProcessDefinition<?, ?>> versions = definitions.get(key);
        ProcessDefinition<?, ?> definition = versions == null ? null : versions.get(version);
        if (definition == null) {
            throw new IllegalArgumentException(
                    "Unknown process definition: " + key + " v" + version);
        }
        return definition;
    }

    /** Latest definition for each stable key, in registration-key order. */
    public List<ProcessDefinition<?, ?>> all() {
        List<ProcessDefinition<?, ?>> latest = new ArrayList<>();
        definitions.values().forEach(versions -> latest.add(versions.lastEntry().getValue()));
        return List.copyOf(latest);
    }

    /** Every registered definition version, ordered by key registration and ascending version. */
    public List<ProcessDefinition<?, ?>> allVersions() {
        return definitions.values().stream()
                .flatMap(versions -> versions.values().stream())
                .toList();
    }

    /** Registered versions for one stable definition key, in ascending order. */
    public List<ProcessDefinition<?, ?>> versions(String key) {
        NavigableMap<Integer, ProcessDefinition<?, ?>> versions = definitions.get(key);
        if (versions == null) {
            throw new IllegalArgumentException("Unknown process definition: " + key);
        }
        return List.copyOf(versions.values());
    }

    /**
     * Resolve the deterministic migration chain from a stored version to the latest version.
     *
     * @throws IllegalArgumentException when the stored version is unknown or no complete path exists
     */
    public List<ProcessDefinitionMigration<?, ?, ?, ?>> migrationPath(
            String key, int storedVersion) {
        ProcessDefinition<?, ?> latest = require(key);
        return migrationPath(key, storedVersion, latest.version());
    }

    /**
     * Resolve a deterministic forward migration chain between two registered versions.
     *
     * <p>Each source version may have only one outgoing migration, so lookup can never silently
     * choose between competing upgrade routes.</p>
     */
    public List<ProcessDefinitionMigration<?, ?, ?, ?>> migrationPath(
            String key, int fromVersion, int toVersion) {
        require(key, fromVersion);
        require(key, toVersion);
        if (fromVersion > toVersion) {
            throw new IllegalArgumentException(
                    "Cannot migrate " + key + " backwards from v"
                            + fromVersion + " to v" + toVersion);
        }
        if (fromVersion == toVersion) {
            return List.of();
        }

        List<ProcessDefinitionMigration<?, ?, ?, ?>> path = new ArrayList<>();
        int current = fromVersion;
        while (current < toVersion) {
            ProcessDefinitionMigration<?, ?, ?, ?> migration =
                    migrations.get(new DefinitionVersion(key, current));
            if (migration == null) {
                throw new IllegalArgumentException(
                        "No process-definition migration path for " + key
                                + " from v" + fromVersion + " to v" + toVersion
                                + "; missing edge from v" + current);
            }
            int next = migration.to().version();
            if (next > toVersion) {
                throw new IllegalArgumentException(
                        "Process-definition migration for " + key + " jumps from v"
                                + current + " past requested v" + toVersion + " to v" + next);
            }
            path.add(migration);
            current = next;
        }
        return List.copyOf(path);
    }

    private static void validateAndAddMigration(
            Map<String, NavigableMap<Integer, ProcessDefinition<?, ?>>> definitions,
            Map<DefinitionVersion, ProcessDefinitionMigration<?, ?, ?, ?>> migrations,
            ProcessDefinitionMigration<?, ?, ?, ?> migration) {
        Objects.requireNonNull(migration, "migration");
        ProcessDefinition<?, ?> from =
                Objects.requireNonNull(migration.from(), "migration from definition");
        ProcessDefinition<?, ?> to =
                Objects.requireNonNull(migration.to(), "migration to definition");
        if (!from.key().equals(to.key())) {
            throw new InvalidProcessDefinitionException(
                    "Process-definition migration crosses keys: "
                            + from.key() + " -> " + to.key());
        }
        if (from.version() >= to.version()) {
            throw new InvalidProcessDefinitionException(
                    "Process-definition migration must move forward: "
                            + from.key() + " v" + from.version() + " -> v" + to.version());
        }
        NavigableMap<Integer, ProcessDefinition<?, ?>> versions = definitions.get(from.key());
        if (versions == null
                || versions.get(from.version()) != from
                || versions.get(to.version()) != to) {
            throw new InvalidProcessDefinitionException(
                    "Process-definition migration endpoints must be registered definition instances: "
                            + from.key() + " v" + from.version() + " -> v" + to.version());
        }
        DefinitionVersion source = new DefinitionVersion(from.key(), from.version());
        if (migrations.putIfAbsent(source, migration) != null) {
            throw new InvalidProcessDefinitionException(
                    "Multiple process-definition migrations start at "
                            + from.key() + " v" + from.version());
        }
    }

    private record DefinitionVersion(String key, int version) {
    }
}
