package su.onno.command;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Fluent declaration of one command, from {@link CommandSpec#command(String)} or
 * {@link CommandSpec#collectionCommand(String)}. Setters may be called in any order;
 * {@link #run(Function)} is required.
 *
 * <pre>
 * c.command("cancel")
 *  .title("Cancel order")
 *  .description("Cancels the order; it can no longer be fulfilled.")
 *  .roles("MANAGER")
 *  .availableWhen(row -&gt; !terminal(row.enumValue("status", OrderStatus.class)))
 *  .input("reason", i -&gt; i.title("Reason").longText().required())
 *  .run(ctx -&gt; { orders.cancel(ctx.id(), ctx.input("reason")); return CommandResult.reload(); });
 * </pre>
 *
 * @param <E> the owning entity
 */
public final class CommandBuilder<E> {

    private final String key;
    private final CommandTarget target;
    private String title;
    private Function<RecordRow<E>, String> titleFn;
    private String description = "";
    private String group = "";
    private List<String> roles = List.of();
    private Predicate<RecordRow<E>> availableFn;
    private Predicate<RecordRow<E>> enabledFn;
    private final List<InputBuilder> inputs = new ArrayList<>();
    private final List<InputRowsBuilder> inputGroups = new ArrayList<>();
    private Function<CommandContext, CommandDefaults> defaultsFn;
    private Function<CommandContext, CommandResult> handler;

    CommandBuilder(String key, CommandTarget target) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Command key must not be blank");
        }
        this.key = key;
        this.target = target;
    }

    /**
     * The fixed human title. Surfaces without a single record in hand (a batch over a selection,
     * an MCP tool) use it; it is also the fallback of a per-record {@link #title(Function)}.
     * Defaults to the key.
     */
    public CommandBuilder<E> title(String title) {
        this.title = title;
        return this;
    }

    /** A per-record title (record commands only), e.g. {@code "Resume"} vs {@code "Suspend"}. */
    public CommandBuilder<E> title(Function<RecordRow<E>, String> title) {
        this.titleFn = title;
        return this;
    }

    /** What the command does, in a sentence or two — the MCP tool description and a UI tooltip. */
    public CommandBuilder<E> description(String description) {
        this.description = description;
        return this;
    }

    /**
     * An optional semantic grouping label shared by related commands (e.g. one command per status
     * under {@code "Change status"}). A UI shows a group as a submenu; MCP ignores it.
     */
    public CommandBuilder<E> group(String group) {
        this.group = group;
        return this;
    }

    /**
     * Restrict the command to callers holding any of the given roles ({@code ADMIN} always
     * passes). This is a finer gate on top of the entity's write roles, enforced on every surface.
     * Unset (the default) means every caller who may write the entity.
     */
    public CommandBuilder<E> roles(String... roles) {
        this.roles = List.of(roles);
        return this;
    }

    /**
     * Offer and allow the command only on records where the predicate holds (record commands
     * only). Elsewhere the command is not listed and running it is rejected.
     */
    public CommandBuilder<E> availableWhen(Predicate<RecordRow<E>> available) {
        this.availableFn = available;
        return this;
    }

    /**
     * Allow the command only on records where the predicate holds (record commands only); unlike
     * {@link #availableWhen}, a client still lists it, disabled.
     */
    public CommandBuilder<E> enabledWhen(Predicate<RecordRow<E>> enabled) {
        this.enabledFn = enabled;
        return this;
    }

    /**
     * Declare a scalar input the caller supplies before the command runs (a UI collects it in a
     * dialog, MCP takes it as a tool argument). The value reaches the handler as
     * {@link CommandContext#input(String)}.
     */
    public CommandBuilder<E> input(String key, Consumer<InputBuilder> input) {
        InputBuilder b = new InputBuilder(key);
        input.accept(b);
        inputs.add(b);
        return this;
    }

    /**
     * Declare a repeatable row group (a transient tabular grid) the caller supplies — each row a
     * set of typed columns. The handler reads the rows via {@link CommandContext#inputRows(String)}.
     */
    public CommandBuilder<E> inputRows(String key, Consumer<InputRowsBuilder> rows) {
        InputRowsBuilder b = new InputRowsBuilder(key);
        rows.accept(b);
        inputGroups.add(b);
        return this;
    }

    /**
     * Compute the inputs' opening values server-side each time a caller prepares to run the
     * command ({@code GET /api/commands/{kind}/{name}/{key}/defaults}). The function receives a
     * {@link CommandContext} with the record id (record commands) and the caller; its inputs are
     * empty. It must be read-only. A thrown exception falls back to the static defaults.
     */
    public CommandBuilder<E> defaults(Function<CommandContext, CommandDefaults> defaults) {
        this.defaultsFn = defaults;
        return this;
    }

    /** What the command does. Required. */
    public CommandBuilder<E> run(Function<CommandContext, CommandResult> handler) {
        this.handler = handler;
        return this;
    }

    Command<E> build() {
        if (handler == null) {
            throw new IllegalStateException("Command '" + key + "' declares no run(...) handler");
        }
        if (target == CommandTarget.COLLECTION && (titleFn != null || availableFn != null || enabledFn != null)) {
            throw new IllegalStateException("Collection command '" + key
                    + "' cannot declare per-record title/availableWhen/enabledWhen");
        }
        return new Command<>(key, target, title != null && !title.isBlank() ? title : key, titleFn,
                description, group, roles, availableFn, enabledFn,
                inputs.stream().map(InputBuilder::build).toList(),
                inputGroups.stream().map(InputRowsBuilder::build).toList(),
                defaultsFn, handler);
    }
}
