package su.onno.mcp;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Customizes an input parameter of an {@link McpTool} method.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface McpToolParam {

    /** Input property name. Defaults to the Java parameter name. */
    String name() default "";

    /** Description shown in the generated input schema. */
    String description() default "";

    /** Whether MCP clients must supply the input. */
    boolean required() default true;

    /** Optional complete JSON Schema for this input, overriding type inference. */
    String schema() default "";
}
