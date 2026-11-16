package su.onno.command;

/**
 * The commands of one catalog or document: what can be <em>done</em> with its records beyond
 * create/update/delete. Register as a Spring bean; several beans for the same entity merge (the
 * first declaring a key wins).
 *
 * <p>A command is behaviour, not a button. The framework exposes every command on every surface —
 * the REST API ({@code /api/commands/...}), the UI, and MCP tools for AI agents — and enforces the
 * same rules everywhere: entity write access, the command's {@code roles}, read-only mode, record
 * visibility and the per-record {@code availableWhen}/{@code enabledWhen} predicates.</p>
 *
 * <pre>
 * &#64;Component
 * class OrderCommands implements Commands&lt;Order&gt; {
 *     public Class&lt;Order&gt; entity() { return Order.class; }
 *
 *     public void define(CommandSpec&lt;Order&gt; c) {
 *         c.command("cancel")
 *          .title("Cancel order")
 *          .description("Cancels the order; it can no longer be fulfilled.")
 *          .roles("MANAGER")
 *          .availableWhen(row -&gt; row.enumValue(Order::getStatus, OrderStatus.class) != OrderStatus.CANCELLED)
 *          .input("reason", i -&gt; i.title("Reason").longText().required())
 *          .run(ctx -&gt; { orders.cancel(ctx.id(), ctx.input("reason")); return CommandResult.reload(); });
 *     }
 * }
 * </pre>
 *
 * @param <E> the catalog or document class
 */
public interface Commands<E> {

    /** The catalog or document class these commands belong to. */
    Class<E> entity();

    /** Declare the entity's commands. */
    void define(CommandSpec<E> commands);
}
