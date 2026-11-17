package su.onno.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Collects the commands of one entity, from {@link Commands#define(CommandSpec)}.
 *
 * <ul>
 *   <li>{@link #command(String)} — a <b>record</b> command: it runs on one record, whose id the
 *       caller must supply ({@link CommandContext#id()}). Its title, availability and enabled state
 *       may vary per record.</li>
 *   <li>{@link #collectionCommand(String)} — a <b>collection</b> command: it runs on the entity as a
 *       whole (a bulk import, a report, a "receive shipment" wizard); there is no record id.</li>
 *   <li>{@link #dynamic(Consumer)} — commands whose keys come from live business data (one
 *       "set status" command per row of an editable status catalog), re-evaluated each time the
 *       command list is requested and when such a command runs.</li>
 * </ul>
 *
 * <p>Where and how a client presents a command (inline button, context menu, toolbar, an AI tool)
 * is the client's business; the only presentation hint is the optional semantic
 * {@link CommandBuilder#group(String) group}.</p>
 *
 * @param <E> the owning entity
 */
public final class CommandSpec<E> {

    private final List<CommandBuilder<E>> builders = new ArrayList<>();
    private final List<Declaration<E>> declarations = new ArrayList<>();

    /** Declare a record command with the given unique key. */
    public CommandBuilder<E> command(String key) {
        return add(key, CommandTarget.RECORD);
    }

    /** Declare a collection command (no record id) with the given unique key. */
    public CommandBuilder<E> collectionCommand(String key) {
        return add(key, CommandTarget.COLLECTION);
    }

    private CommandBuilder<E> add(String key, CommandTarget target) {
        CommandBuilder<E> b = new CommandBuilder<>(key, target);
        builders.add(b);
        declarations.add(new StaticDeclaration<>(b));
        return b;
    }

    /**
     * Declare commands whose keys and titles come from live business data. The provider is
     * retained at startup and evaluated each time the entity's command list is requested and
     * again when one of its keys runs; it is not a cache or a startup callback. Each evaluation
     * receives a fresh {@code CommandSpec}, so it declares ordinary commands with the same builder:
     *
     * <pre>
     * c.dynamic(live -&gt; {
     *     for (Status status : statuses.all()) {
     *         live.command("setStatus_" + status.id()).group("Change status")
     *             .title(status.label())
     *             .run(ctx -&gt; setStatus(ctx.id(), status.id()));
     *     }
     * });
     * </pre>
     *
     * <p>The provider should be read-only and fast. Declaration order is preserved, and the first
     * declaration of a duplicate key wins when the commands of all beans are merged.</p>
     */
    public CommandSpec<E> dynamic(Consumer<CommandSpec<E>> provider) {
        declarations.add(new DynamicDeclaration<>(Objects.requireNonNull(provider, "provider")));
        return this;
    }

    /** Static declarations only; dynamic providers are deliberately not evaluated here. */
    public List<Command<E>> commands() {
        return builders.stream().map(CommandBuilder<E>::build).toList();
    }

    /** Whether this spec contains at least one late-bound command provider. */
    public boolean hasDynamicCommands() {
        return declarations.stream().anyMatch(DynamicDeclaration.class::isInstance);
    }

    /** Resolve static and late-bound declarations in authored order. */
    public List<Command<E>> resolveCommands() {
        List<Command<E>> out = new ArrayList<>();
        for (Declaration<E> declaration : declarations) {
            out.addAll(declaration.resolve());
        }
        return List.copyOf(out);
    }

    private sealed interface Declaration<E> permits StaticDeclaration, DynamicDeclaration {
        List<Command<E>> resolve();
    }

    private record StaticDeclaration<E>(CommandBuilder<E> builder) implements Declaration<E> {
        @Override
        public List<Command<E>> resolve() {
            return List.of(builder.build());
        }
    }

    private record DynamicDeclaration<E>(Consumer<CommandSpec<E>> provider) implements Declaration<E> {
        @Override
        public List<Command<E>> resolve() {
            CommandSpec<E> live = new CommandSpec<>();
            provider.accept(live);
            return live.resolveCommands();
        }
    }
}
