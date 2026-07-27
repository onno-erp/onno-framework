package su.onno.process;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Typed definition of one business-process route.
 *
 * @param <P> process payload type
 * @param <S> enum containing this process's stable step keys
 */
public abstract class ProcessDefinition<P, S extends Enum<S> & ProcessStepKey> {

    private static final ThreadLocal<Set<ProcessDefinition<?, ?>>> BUILD_STACK =
            ThreadLocal.withInitial(LinkedHashSet::new);

    private final String key;
    private final int version;
    private final Class<P> payloadType;
    private volatile ProcessGraph<P, S> graph;
    private volatile String fingerprint;

    protected ProcessDefinition(String key, Class<P> payloadType) {
        this(key, 1, payloadType);
    }

    protected ProcessDefinition(String key, int version, Class<P> payloadType) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Process definition key must not be blank");
        }
        if (version < 1) {
            throw new IllegalArgumentException("Process definition version must be positive");
        }
        this.key = key.trim();
        this.version = version;
        this.payloadType = java.util.Objects.requireNonNull(payloadType, "payloadType");
    }

    /** Stable persisted definition identity. Never derived from a display title or class name. */
    public final String key() {
        return key;
    }

    /** Persisted definition version used to resume and migrate durable instances safely. */
    public final int version() {
        return version;
    }

    /** Payload type used by the durable runtime's JSON codec. */
    public final Class<P> payloadType() {
        return payloadType;
    }

    /**
     * Deterministic SHA-256 identity of this version's payload type and route structure.
     *
     * <p>A durable runtime persists this value and rejects a same-key, same-version definition
     * whose graph later changes. Bump {@link #version()} and provide a migration instead.</p>
     */
    public final String fingerprint() {
        String current = fingerprint;
        if (current == null) {
            synchronized (this) {
                current = fingerprint;
                if (current == null) {
                    current = calculateFingerprint(graph().descriptor());
                    fingerprint = current;
                }
            }
        }
        return current;
    }

    /** Human-facing process title. */
    public String title() {
        return getClass().getSimpleName();
    }

    /**
     * Candidate users/roles allowed to start this process through a generic user-facing boundary.
     * Ordinary trusted Java services may still call {@link ProcessEngine#start} directly.
     */
    public abstract TaskAssignment startAssignment(P payload);

    /**
     * Users/roles allowed to cancel an active instance.
     *
     * <p>By default the same audience that can start the process may cancel it. Definitions may
     * narrow or broaden that audience independently.</p>
     */
    public TaskAssignment cancellationAssignment(P payload) {
        return startAssignment(payload);
    }

    /** Declare tasks, endings, and typed transitions. Called once on first graph access. */
    protected abstract void define(ProcessGraph<P, S> graph);

    /** Validated immutable graph for this definition. */
    public final ProcessGraph<P, S> graph() {
        ProcessGraph<P, S> current = graph;
        if (current == null) {
            Set<ProcessDefinition<?, ?>> stack = BUILD_STACK.get();
            if (stack.contains(this)) {
                String cycle = java.util.stream.Stream.concat(
                                stack.stream(), java.util.stream.Stream.of(this))
                        .map(definition -> definition.key() + " v" + definition.version())
                        .collect(java.util.stream.Collectors.joining(" -> "));
                throw new InvalidProcessDefinitionException(
                        "Subprocess definition cycle: " + cycle);
            }
            synchronized (this) {
                current = graph;
                if (current == null) {
                    stack.add(this);
                    try {
                        current = new ProcessGraph<>();
                        define(current);
                        current.validateAndSeal();
                        graph = current;
                    } finally {
                        stack.remove(this);
                        if (stack.isEmpty()) {
                            BUILD_STACK.remove();
                        }
                    }
                }
            }
        }
        return current;
    }

    private String calculateFingerprint(ProcessGraphDescriptor descriptor) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateDigest(digest, key);
            updateDigest(digest, Integer.toString(version));
            updateDigest(digest, payloadType.getName());
            updateDigest(digest, descriptor.startStepKey());
            updateDigest(digest, Integer.toString(descriptor.nodes().size()));
            descriptor.nodes().stream()
                    .sorted(Comparator.comparing(ProcessNodeDescriptor::stepKey))
                    .forEach(node -> {
                        updateDigest(digest, node.stepKey());
                        updateDigest(digest, node.type().name());
                        updateDigest(digest, Integer.toString(node.routes().size()));
                        node.routes().entrySet().stream()
                                .sorted(MapEntryComparator.INSTANCE)
                                .forEach(route -> {
                                    updateDigest(digest, route.getKey());
                                    updateDigest(digest, route.getValue());
                                });
                    });
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }

    private static void updateDigest(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private enum MapEntryComparator
            implements Comparator<java.util.Map.Entry<String, String>> {
        INSTANCE;

        @Override
        public int compare(
                java.util.Map.Entry<String, String> left,
                java.util.Map.Entry<String, String> right) {
            int byKey = left.getKey().compareTo(right.getKey());
            return byKey != 0 ? byKey : left.getValue().compareTo(right.getValue());
        }
    }
}
