package su.onno.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import su.onno.ui.UiProperties;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AnnotatedMcpToolProviderTest {

    private final McpJsonMapper json =
            new JacksonMcpJsonMapper(new ObjectMapper().findAndRegisterModules());

    @Test
    void discoversSchemaAndInvokesTypedBeanMethodAsCaller() {
        OnnoMcpProperties properties = new OnnoMcpProperties();
        try (GenericApplicationContext context = contextWith(SampleTools.class)) {
            SyncToolSpecification tool = new AnnotatedMcpToolProvider(
                    context.getBeanFactory(), properties, json).tools().getFirst();

            assertThat(tool.tool().name()).isEqualTo("shipping_quote");
            assertThat(tool.tool().title()).isEqualTo("Quote shipping");
            assertThat(tool.tool().annotations().readOnlyHint()).isFalse();
            assertThat(tool.tool().inputSchema().required()).containsExactly("packages");
            assertThat(tool.tool().inputSchema().properties().get("speed"))
                    .asString().contains("STANDARD", "EXPRESS");

            CallToolResult result = tool.callHandler().apply(exchange("maria", "OPS"),
                    new CallToolRequest("shipping_quote",
                            Map.of("packages", 3, "speed", "EXPRESS")));

            assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
            assertThat(result.content().toString()).contains("maria", "EXPRESS", "3");
        }
    }

    @Test
    void deniesAnonymousAndWrongRole() {
        try (GenericApplicationContext context = contextWith(SampleTools.class)) {
            SyncToolSpecification tool = new AnnotatedMcpToolProvider(
                    context.getBeanFactory(), new OnnoMcpProperties(), json).tools().getFirst();

            CallToolResult anonymous = tool.callHandler().apply(null,
                    new CallToolRequest("shipping_quote", Map.of("packages", 1)));
            CallToolResult wrongRole = tool.callHandler().apply(exchange("maria", "SALES"),
                    new CallToolRequest("shipping_quote", Map.of("packages", 1)));

            assertThat(anonymous.isError()).isTrue();
            assertThat(anonymous.content().toString()).contains("Authentication required");
            assertThat(wrongRole.isError()).isTrue();
            assertThat(wrongRole.content().toString()).contains("Access denied");
        }
    }

    @Test
    void globalWriteGateOmitsNonReadOnlyAnnotatedTools() {
        OnnoMcpProperties properties = new OnnoMcpProperties();
        properties.setWritesEnabled(false);
        try (GenericApplicationContext context = contextWith(SampleTools.class)) {
            assertThat(new AnnotatedMcpToolProvider(
                    context.getBeanFactory(), properties, json).tools()).isEmpty();
        }
    }

    @Test
    void uiReadOnlyGateOmitsNonReadOnlyAnnotatedTools() {
        UiProperties ui = new UiProperties();
        ui.setReadOnly(true);
        try (GenericApplicationContext context = contextWith(SampleTools.class)) {
            assertThat(new AnnotatedMcpToolProvider(
                    context.getBeanFactory(), new OnnoMcpProperties(), ui, json).tools()).isEmpty();
        }
    }

    @Test
    void mergeRejectsDuplicateToolNamesAcrossProviders() {
        SyncToolSpecification duplicate;
        try (GenericApplicationContext context = contextWith(SampleTools.class)) {
            duplicate = new AnnotatedMcpToolProvider(
                    context.getBeanFactory(), new OnnoMcpProperties(), json).tools().getFirst();
        }
        McpToolProvider first = () -> List.of(duplicate);
        McpToolProvider second = () -> List.of(duplicate);

        assertThatThrownBy(() -> OnnoMcpAutoConfiguration.mergeTools(List.of(first, second)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Duplicate MCP tool name: shipping_quote");
    }

    private static GenericApplicationContext contextWith(Class<?> beanType) {
        GenericApplicationContext context = new GenericApplicationContext();
        context.registerBean(beanType);
        context.refresh();
        return context;
    }

    private static McpSyncServerExchange exchange(String username, String role) {
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                username, "n/a", List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        McpTransportContext transport = McpTransportContext.create(
                Map.of(McpPrincipalContext.PRINCIPAL_KEY, authentication));
        McpSyncServerExchange exchange = mock(McpSyncServerExchange.class);
        when(exchange.transportContext()).thenReturn(transport);
        return exchange;
    }

    enum Speed {
        STANDARD, EXPRESS
    }

    static class SampleTools {

        @McpTool(name = "shipping_quote", title = "Quote shipping",
                description = "Quotes a shipment", roles = "OPS",
                readOnly = false, idempotent = true)
        public Map<String, Object> quote(
                @McpToolParam(description = "Number of packages") int packages,
                @McpToolParam(required = false) Optional<Speed> speed,
                Principal principal) {
            return Map.of(
                    "packages", packages,
                    "speed", speed.orElse(Speed.STANDARD).name(),
                    "requestedBy", principal.getName());
        }
    }
}
