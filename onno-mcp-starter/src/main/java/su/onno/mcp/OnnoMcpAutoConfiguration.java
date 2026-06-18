package su.onno.mcp;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import su.onno.metadata.MetadataRegistry;
import su.onno.ui.CatalogCommandService;
import su.onno.ui.CatalogQueryService;
import su.onno.ui.DocumentCommandService;
import su.onno.ui.DocumentQueryService;
import su.onno.ui.RegisterQueryService;
import su.onno.ui.UiAccessService;
import su.onno.ui.UiAutoConfiguration;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Auto-configuration for the onno MCP server.
 *
 * <p>Mounts a Model Context Protocol streamable-HTTP endpoint that exposes the
 * application's metadata-driven business model as tools. Tools are generated generically
 * from the {@link MetadataRegistry} and every call is enforced through the same
 * {@link UiAccessService} role model as the web UI, running as the authenticated user
 * captured by {@link McpPrincipalContext}.
 *
 * <p>Requires Spring Security on the classpath (the identity bridge has no meaning
 * without it); the {@code /mcp} HTTP Basic filter chain is contributed by
 * {@link McpSecurityConfiguration}.
 */
@AutoConfiguration(after = UiAutoConfiguration.class,
        afterName = "su.onno.auth.OnnoAuthAutoConfiguration")
@ConditionalOnClass({McpServer.class, HttpServletStreamableServerTransportProvider.class,
        SecurityContextHolder.class})
@ConditionalOnBean(MetadataRegistry.class)
@EnableConfigurationProperties(OnnoMcpProperties.class)
@ConditionalOnProperty(prefix = "onno.mcp", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OnnoMcpAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public McpJsonMapper onnoMcpJsonMapper() {
        ObjectMapper mapper = new ObjectMapper()
                .findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        return new JacksonMcpJsonMapper(mapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public MetadataToolFactory metadataToolFactory(MetadataRegistry registry, UiAccessService access,
                                                   CatalogQueryService catalogQuery,
                                                   DocumentQueryService documentQuery,
                                                   RegisterQueryService registerQuery,
                                                   CatalogCommandService catalogCommands,
                                                   DocumentCommandService documentCommands,
                                                   OnnoMcpProperties properties, McpJsonMapper json) {
        return new MetadataToolFactory(registry, access, catalogQuery, documentQuery, registerQuery,
                catalogCommands, documentCommands, properties, json);
    }

    @Bean
    @ConditionalOnMissingBean
    public HttpServletStreamableServerTransportProvider onnoMcpTransportProvider(McpJsonMapper json,
                                                                                 OnnoMcpProperties properties) {
        return HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(json)
                .mcpEndpoint(properties.getEndpoint())
                .contextExtractor(new McpPrincipalContext())
                .build();
    }

    @Bean
    public ServletRegistrationBean<HttpServletStreamableServerTransportProvider> onnoMcpServletRegistration(
            HttpServletStreamableServerTransportProvider provider, OnnoMcpProperties properties) {
        ServletRegistrationBean<HttpServletStreamableServerTransportProvider> registration =
                new ServletRegistrationBean<>(provider, properties.getEndpoint());
        registration.setName("onnoMcpServlet");
        registration.setAsyncSupported(true);
        registration.setLoadOnStartup(1);
        return registration;
    }

    @Bean
    @ConditionalOnMissingBean
    public McpSyncServer onnoMcpServer(HttpServletStreamableServerTransportProvider provider,
                                       MetadataToolFactory toolFactory, OnnoMcpProperties properties,
                                       McpJsonMapper json) {
        String instructions = properties.getInstructions() == null || properties.getInstructions().isBlank()
                ? "Tools to read and edit this onno business application (catalogs, documents, registers). "
                + "Call describe_metadata first to learn entity and field names. All access is enforced by "
                + "the connecting user's roles."
                : properties.getInstructions();

        return McpServer.sync(provider)
                .serverInfo(properties.getServerName(), properties.getServerVersion())
                .capabilities(ServerCapabilities.builder().tools(true).build())
                .instructions(instructions)
                .tools(toolFactory.build())
                .build();
    }
}
