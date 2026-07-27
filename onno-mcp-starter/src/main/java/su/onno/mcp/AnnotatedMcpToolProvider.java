package su.onno.mcp;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;

import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.security.Principal;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Discovers {@link McpTool} methods on Spring beans and adapts them to MCP SDK tools.
 */
final class AnnotatedMcpToolProvider implements McpToolProvider {

    private static final Pattern TOOL_NAME = Pattern.compile("[A-Za-z0-9_-]{1,128}");
    private static final Set<Class<?>> INJECTED_TYPES = Set.of(
            Principal.class, McpToolContext.class, McpSyncServerExchange.class);

    private final ConfigurableListableBeanFactory beans;
    private final OnnoMcpProperties properties;
    private final McpJsonMapper json;

    AnnotatedMcpToolProvider(ConfigurableListableBeanFactory beans, OnnoMcpProperties properties,
                             McpJsonMapper json) {
        this.beans = beans;
        this.properties = properties;
        this.json = json;
    }

    @Override
    public List<SyncToolSpecification> tools() {
        List<SyncToolSpecification> result = new ArrayList<>();
        for (String beanName : beans.getBeanDefinitionNames()) {
            Class<?> beanType = beans.getType(beanName, false);
            if (beanType == null) continue;
            Map<Method, McpTool> methods = MethodIntrospector.selectMethods(beanType,
                    (MethodIntrospector.MetadataLookup<McpTool>) method ->
                            AnnotatedElementUtils.findMergedAnnotation(method, McpTool.class));
            methods.forEach((method, annotation) -> {
                if (annotation.readOnly() || properties.isWritesEnabled()) {
                    result.add(adapt(beanName, method, annotation));
                }
            });
        }
        return result;
    }

    private SyncToolSpecification adapt(String beanName, Method method, McpTool annotation) {
        validateMethod(method);
        String name = annotation.name().isBlank() ? method.getName() : annotation.name().trim();
        if (!TOOL_NAME.matcher(name).matches()) {
            throw new IllegalStateException("Invalid @McpTool name '" + name + "' on " + method);
        }
        String title = annotation.title().isBlank() ? name : annotation.title().trim();
        Map<String, Object> inputSchema = inputSchema(method);

        Tool.Builder builder = Tool.builder()
                .name(name)
                .title(title)
                .description(annotation.description())
                .inputSchema(json, writeJson(inputSchema))
                .annotations(new ToolAnnotations(title, annotation.readOnly(), annotation.destructive(),
                        annotation.idempotent(), annotation.openWorld(), false));
        if (!annotation.outputSchema().isBlank()) {
            Map<String, Object> outputSchema = new LinkedHashMap<>();
            outputSchema.put("type", "object");
            outputSchema.put("properties", Map.of("result",
                    parseSchema(annotation.outputSchema(), method, "return value")));
            outputSchema.put("required", List.of("result"));
            outputSchema.put("additionalProperties", false);
            builder.outputSchema(json, writeJson(outputSchema));
        }

        return SyncToolSpecification.builder()
                .tool(builder.build())
                .callHandler((exchange, request) -> invoke(beanName, method, annotation, exchange, request))
                .build();
    }

    private CallToolResult invoke(String beanName, Method method, McpTool annotation,
                                  McpSyncServerExchange exchange, CallToolRequest request) {
        Principal principal = McpPrincipalContext.principal(exchange);
        if (principal == null) return error("Authentication required");
        if (!hasAnyRole(principal, annotation.roles())) return error("Access denied");

        try {
            Object bean = beans.getBean(beanName);
            Method invocable = MethodIntrospector.selectInvocableMethod(method, bean.getClass());
            Map<String, Object> arguments = request.arguments() == null ? Map.of() : request.arguments();
            Object value = invocable.invoke(bean, bind(method, exchange, principal, arguments));
            if (value instanceof CallToolResult result) return result;
            return ok(method.getReturnType() == Void.TYPE ? Map.of("success", true) : value);
        } catch (InvocationTargetException exception) {
            return invocationError(exception.getTargetException());
        } catch (Exception exception) {
            return invocationError(exception);
        }
    }

    private Object[] bind(Method method, McpSyncServerExchange exchange, Principal principal,
                          Map<String, Object> arguments) {
        Parameter[] parameters = method.getParameters();
        Object[] values = new Object[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            Parameter parameter = parameters[i];
            if (parameter.getType() == Principal.class) {
                values[i] = principal;
            } else if (parameter.getType() == McpToolContext.class) {
                values[i] = new McpToolContext(principal, exchange);
            } else if (parameter.getType() == McpSyncServerExchange.class) {
                values[i] = exchange;
            } else {
                String name = parameterName(parameter);
                Object raw = arguments.get(name);
                McpToolParam config = parameter.getAnnotation(McpToolParam.class);
                boolean required = config == null
                        ? parameter.getType() != Optional.class
                        : config.required();
                if (raw == null && required) {
                    throw new IllegalArgumentException("Missing required argument: " + name);
                }
                values[i] = convert(raw, parameter);
            }
        }
        return values;
    }

    private Object convert(Object raw, Parameter parameter) {
        if (parameter.getType() == Optional.class) {
            if (raw == null) return Optional.empty();
            Type type = parameter.getParameterizedType();
            if (type instanceof ParameterizedType parameterized
                    && parameterized.getActualTypeArguments()[0] instanceof Class<?> valueType) {
                return Optional.ofNullable(json.convertValue(raw, valueType));
            }
            return Optional.of(raw);
        }
        if (raw == null) return null;
        return json.convertValue(raw, parameter.getType());
    }

    private Map<String, Object> inputSchema(Method method) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (Parameter parameter : method.getParameters()) {
            if (INJECTED_TYPES.contains(parameter.getType())) continue;
            McpToolParam config = parameter.getAnnotation(McpToolParam.class);
            String name = parameterName(parameter);
            if (properties.containsKey(name)) {
                throw new IllegalStateException("Duplicate @McpTool parameter name '" + name + "' on " + method);
            }
            Map<String, Object> schema = config != null && !config.schema().isBlank()
                    ? parseSchema(config.schema(), method, name)
                    : inferredSchema(parameter.getParameterizedType());
            if (config != null && !config.description().isBlank()) {
                schema.put("description", config.description());
            }
            properties.put(name, schema);
            boolean isRequired = config == null
                    ? parameter.getType() != Optional.class
                    : config.required();
            if (isRequired) required.add(name);
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) schema.put("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }

    private Map<String, Object> inferredSchema(Type type) {
        if (type instanceof ParameterizedType parameterized) {
            Type raw = parameterized.getRawType();
            if (raw == Optional.class) {
                return inferredSchema(parameterized.getActualTypeArguments()[0]);
            }
            if (raw instanceof Class<?> rawClass && Collection.class.isAssignableFrom(rawClass)) {
                return new LinkedHashMap<>(Map.of("type", "array",
                        "items", inferredSchema(parameterized.getActualTypeArguments()[0])));
            }
            if (raw instanceof Class<?> rawClass && Map.class.isAssignableFrom(rawClass)) {
                return new LinkedHashMap<>(Map.of("type", "object"));
            }
        }
        if (!(type instanceof Class<?> valueType)) {
            return new LinkedHashMap<>(Map.of("type", "object"));
        }
        if (valueType.isEnum()) {
            List<String> values = new ArrayList<>();
            for (Object constant : valueType.getEnumConstants()) values.add(((Enum<?>) constant).name());
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "string");
            schema.put("enum", values);
            return schema;
        }
        if (valueType == boolean.class || valueType == Boolean.class) return schema("boolean");
        if (valueType == byte.class || valueType == short.class || valueType == int.class
                || valueType == long.class || valueType == Byte.class || valueType == Short.class
                || valueType == Integer.class || valueType == Long.class
                || valueType == BigInteger.class) return schema("integer");
        if (valueType == float.class || valueType == double.class || valueType == Float.class
                || valueType == Double.class || valueType == BigDecimal.class) return schema("number");
        if (valueType.isArray()) {
            return new LinkedHashMap<>(Map.of("type", "array",
                    "items", inferredSchema(valueType.getComponentType())));
        }
        if (CharSequence.class.isAssignableFrom(valueType) || valueType == char.class
                || valueType == Character.class || valueType == UUID.class
                || TemporalAccessor.class.isAssignableFrom(valueType)) return schema("string");
        return schema("object");
    }

    private static Map<String, Object> schema(String type) {
        return new LinkedHashMap<>(Map.of("type", type));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseSchema(String value, Method method, String parameter) {
        try {
            return new LinkedHashMap<>(json.readValue(value, Map.class));
        } catch (Exception exception) {
            throw new IllegalStateException("Invalid JSON schema for parameter '" + parameter
                    + "' on " + method, exception);
        }
    }

    private static String parameterName(Parameter parameter) {
        McpToolParam config = parameter.getAnnotation(McpToolParam.class);
        return config == null || config.name().isBlank() ? parameter.getName() : config.name().trim();
    }

    private static void validateMethod(Method method) {
        if (!Modifier.isPublic(method.getModifiers()) || Modifier.isStatic(method.getModifiers())) {
            throw new IllegalStateException("@McpTool method must be a public instance method: " + method);
        }
        for (Parameter parameter : method.getParameters()) {
            if (parameter.getType() == Principal.class || parameter.getType() == McpToolContext.class
                    || parameter.getType() == McpSyncServerExchange.class) continue;
            if (!parameter.isNamePresent()) {
                McpToolParam annotation = parameter.getAnnotation(McpToolParam.class);
                if (annotation == null || annotation.name().isBlank()) {
                    throw new IllegalStateException("@McpTool parameter names require Java -parameters "
                            + "or @McpToolParam(name=...): " + method);
                }
            }
            McpToolParam annotation = parameter.getAnnotation(McpToolParam.class);
            if (parameter.getType().isPrimitive() && annotation != null && !annotation.required()) {
                throw new IllegalStateException("Primitive @McpTool parameter cannot be optional: " + parameter);
            }
        }
    }

    private static boolean hasAnyRole(Principal principal, String[] roles) {
        if (roles.length == 0) return true;
        if (!(principal instanceof Authentication authentication)) return false;
        Set<String> authorities = authentication.getAuthorities().stream()
                .map(authority -> authority.getAuthority().toUpperCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet());
        if (authorities.contains("ADMIN") || authorities.contains("ROLE_ADMIN")) return true;
        for (String role : roles) {
            String normalized = role.trim().toUpperCase(Locale.ROOT);
            if (authorities.contains(normalized) || authorities.contains("ROLE_" + normalized)) return true;
        }
        return false;
    }

    private CallToolResult ok(Object value) {
        try {
            Map<String, Object> structured = new LinkedHashMap<>();
            structured.put("result", value);
            return CallToolResult.builder()
                    .addTextContent(json.writeValueAsString(value))
                    .structuredContent(structured)
                    .build();
        } catch (Exception exception) {
            return error("Failed to serialize result: " + exception.getMessage());
        }
    }

    private static CallToolResult invocationError(Throwable exception) {
        if (exception instanceof ResponseStatusException status) {
            return error(status.getReason() == null ? status.getMessage() : status.getReason());
        }
        if (exception instanceof IllegalArgumentException) return error(exception.getMessage());
        return error(exception.getClass().getSimpleName() + ": " + exception.getMessage());
    }

    private static CallToolResult error(String message) {
        return CallToolResult.builder().isError(true)
                .addTextContent(message == null ? "error" : message).build();
    }

    private String writeJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to build MCP tool schema", exception);
        }
    }
}
