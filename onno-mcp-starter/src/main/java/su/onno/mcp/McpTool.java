package su.onno.mcp;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Exposes a Spring bean method as an MCP tool.
 *
 * <p>Ordinary method parameters become tool inputs. A {@link java.security.Principal},
 * {@link McpToolContext}, or MCP server exchange parameter is supplied by the starter and
 * is not included in the input schema.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface McpTool {

    /** Tool name advertised to MCP clients. Defaults to the Java method name. */
    String name() default "";

    /** Short human-readable title. Defaults to the tool name. */
    String title() default "";

    /** Description shown to MCP clients. */
    String description() default "";

    /** Roles allowed to call the tool. Empty means any authenticated user. */
    String[] roles() default {};

    /** MCP read-only safety hint. Non-read-only tools obey {@code onno.mcp.writes-enabled}. */
    boolean readOnly() default true;

    /** MCP destructive safety hint. */
    boolean destructive() default false;

    /** MCP idempotent safety hint. */
    boolean idempotent() default false;

    /** MCP open-world safety hint. */
    boolean openWorld() default false;

    /** Optional JSON Schema for the returned value. */
    String outputSchema() default "";
}
