package su.onno.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.h2.jdbcx.JdbcDataSource;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import su.onno.metadata.MetadataRegistry;
import su.onno.period.PeriodCloseService;
import su.onno.period.PeriodScope;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PeriodCloseMcpToolsTest {

    @Test
    void periodCloseStatus_listsTheClosesInForce_readOnly() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        PeriodCloseService periods = new PeriodCloseService(Jdbi.create(ds), new MetadataRegistry());
        periods.ensureSchema();
        periods.close(PeriodScope.global(), LocalDate.of(2026, 10, 31), true, "admin", null);

        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(PeriodCloseMcpTools.class, () -> new PeriodCloseMcpTools(periods));
            context.refresh();
            SyncToolSpecification tool = new AnnotatedMcpToolProvider(context.getBeanFactory(),
                    new OnnoMcpProperties(), new JacksonMcpJsonMapper(new ObjectMapper().findAndRegisterModules()))
                    .tools().getFirst();

            assertThat(tool.tool().name()).isEqualTo("period_close_status");
            assertThat(tool.tool().annotations().readOnlyHint()).isTrue();

            McpSyncServerExchange exchange = mock(McpSyncServerExchange.class);
            when(exchange.transportContext()).thenReturn(McpTransportContext.create(Map.of(
                    McpPrincipalContext.PRINCIPAL_KEY, new UsernamePasswordAuthenticationToken("maria", null,
                            List.of(new SimpleGrantedAuthority("ROLE_USER"))))));
            CallToolResult result = tool.callHandler().apply(exchange,
                    new CallToolRequest("period_close_status", Map.of()));

            assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
            assertThat(result.content().toString()).contains("global", "2026-10-31", "hard");
        }
    }
}
