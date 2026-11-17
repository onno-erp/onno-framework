package su.onno.service;

import su.onno.command.CommandRegistry;
import su.onno.metadata.MetadataRegistry;
import su.onno.numbering.NumberGenerator;
import su.onno.posting.PostingService;
import su.onno.spring.OnnoAutoConfiguration;
import su.onno.spring.OnnoProperties;

import org.jdbi.v3.core.Jdbi;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * The transport-neutral service layer over the business model: generic catalog/document/register
 * queries and commands, the command execution path, entity- and record-level access enforcement,
 * and identity resolution. REST controllers (onno-web-starter), MCP tools, CSV import and print
 * forms all call these beans; none of them depends on HTTP. Refusals surface as
 * {@link ServiceException}, which the web starter maps onto HTTP statuses.
 */
@AutoConfiguration(after = OnnoAutoConfiguration.class)
@EnableConfigurationProperties({WebProperties.class, RecordAccessProperties.class})
@ConditionalOnBean(MetadataRegistry.class)
public class OnnoServicesAutoConfiguration {

    @Bean
    public BatchRunner batchRunner(WebProperties webProperties) {
        return new BatchRunner(webProperties.getBatch().getParallelism());
    }

    /** The one command execution path shared by REST, MCP and module endpoints. */
    @Bean
    public CommandService commandService(CatalogQueryService catalogQueryService,
                                         DocumentQueryService documentQueryService,
                                         AccessService access,
                                         CommandRegistry commandRegistry,
                                         WebProperties webProperties,
                                         BatchRunner batchRunner) {
        return new CommandService(catalogQueryService, documentQueryService, access, commandRegistry,
                webProperties, batchRunner);
    }

    @Bean
    public AccessService uiAccessService(MetadataRegistry registry, org.springframework.beans.factory.ObjectProvider<EntityAccessPolicy> policies) {
        return new AccessService(registry, () -> policies.orderedStream().toList());
    }

    /** The validated record policies; boot fails here on an invalid {@code RecordAccessPolicy}. */
    @Bean
    public su.onno.access.RecordPolicies recordPolicies(
            MetadataRegistry registry,
            org.springframework.beans.factory.ObjectProvider<su.onno.access.RecordAccessPolicy> policies) {
        return new su.onno.access.RecordPolicies(registry, policies.orderedStream().toList());
    }

    @Bean
    public RecordScopeCompiler recordScopeCompiler(su.onno.access.RecordPolicies recordPolicies) {
        return new RecordScopeCompiler(recordPolicies);
    }

    @Bean
    public RecordAccess recordAccess(RecordScopeCompiler recordScopeCompiler, AccessService access, Jdbi jdbi,
                                     RecordAccessProperties properties) {
        return new RecordAccess(recordScopeCompiler, access, jdbi, properties.isLogDenials());
    }

    @Bean
    public RecordScopeIndexes recordScopeIndexes(su.onno.access.RecordPolicies recordPolicies, Jdbi jdbi,
                                                 RecordAccessProperties properties,
                                                 OnnoProperties onnoProperties) {
        // The typed property, not the Environment: its "apply" default lives on the field (#460).
        return new RecordScopeIndexes(recordPolicies, jdbi, properties.isCreateIndexes(),
                onnoProperties.getSchema().getMode());
    }

    @Bean
    public AccessSubjectResolver accessSubjectResolver(
            AccessService access, CurrentUserResolver currentUserResolver,
            org.springframework.beans.factory.ObjectProvider<su.onno.access.AccessSubjectContributor> contributors) {
        return new AccessSubjectResolver(access, currentUserResolver, () -> contributors.orderedStream().toList());
    }

    @Bean
    public CatalogQueryService catalogQueryService(MetadataRegistry registry, Jdbi jdbi,
                                                   RecordScopeCompiler recordScopeCompiler, AccessService access) {
        return new CatalogQueryService(registry, jdbi, recordScopeCompiler, access);
    }

    @Bean
    public DocumentQueryService documentQueryService(MetadataRegistry registry, Jdbi jdbi,
                                                     RecordScopeCompiler recordScopeCompiler, AccessService access) {
        return new DocumentQueryService(registry, jdbi, recordScopeCompiler, access);
    }

    @Bean
    public RegisterQueryService registerQueryService(MetadataRegistry registry, Jdbi jdbi,
                                                     RecordScopeCompiler recordScopeCompiler, AccessService access) {
        return new RegisterQueryService(registry, jdbi, recordScopeCompiler, access);
    }

    @Bean
    public InformationRegisterQueryService informationRegisterQueryService(MetadataRegistry registry, Jdbi jdbi,
                                                                           RecordScopeCompiler recordScopeCompiler,
                                                                           AccessService access) {
        return new InformationRegisterQueryService(registry, jdbi, recordScopeCompiler, access);
    }

    /**
     * The generic write path's persistence: Spring Data JDBC's aggregate template, so the command
     * services save through the same callbacks as typed repositories.
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
    public EntityStore entityStore(org.springframework.data.jdbc.core.JdbcAggregateOperations aggregates,
                                   org.springframework.transaction.PlatformTransactionManager transactionManager) {
        return new EntityStore(aggregates, transactionManager);
    }

    @Bean
    public CatalogCommandService catalogCommandService(MetadataRegistry registry, Jdbi jdbi,
                                                       WebProperties properties,
                                                       CatalogQueryService catalogQueryService,
                                                       AccessService access,
                                                       EntityStore entityStore) {
        return new CatalogCommandService(registry, jdbi, properties, catalogQueryService, access, entityStore);
    }

    @Bean
    public DocumentCommandService documentCommandService(MetadataRegistry registry, Jdbi jdbi,
                                                         WebProperties properties,
                                                         PostingService postingService,
                                                         DocumentQueryService documentQueryService,
                                                         AccessService access,
                                                         EntityStore entityStore,
                                                         org.springframework.beans.factory.ObjectProvider<su.onno.period.PeriodCloseService> periodClose) {
        DocumentCommandService service = new DocumentCommandService(registry, jdbi, properties,
                postingService, documentQueryService, access, entityStore);
        service.setPeriodCloseService(periodClose.getIfAvailable());
        return service;
    }

    /**
     * Writes a record together with its join-entity relations in one Spring transaction (the Jdbi
     * handles of the command services join it through {@code SpringConnectionFactory}).
     */
    @Bean
    public RelationWriteService relationWriteService(MetadataRegistry registry, Jdbi jdbi,
                                                     javax.sql.DataSource dataSource,
                                                     org.springframework.beans.factory.ObjectProvider<org.springframework.transaction.PlatformTransactionManager> transactions,
                                                     CatalogQueryService catalogQueryService,
                                                     CatalogCommandService catalogCommandService,
                                                     DocumentQueryService documentQueryService,
                                                     DocumentCommandService documentCommandService,
                                                     RecordAccess recordAccess) {
        org.springframework.transaction.PlatformTransactionManager manager = transactions.getIfAvailable(
                () -> new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
        return new RelationWriteService(registry, jdbi, new org.springframework.transaction.support.TransactionTemplate(manager),
                catalogQueryService, catalogCommandService, documentQueryService, documentCommandService, recordAccess);
    }

    /**
     * Attributes record-history rows written outside a generic command (typed repository saves,
     * {@code PostingService} calls from application code) to the request's authenticated user and
     * their {@code Layout.identity(...)} record. Off a request thread it resolves nothing and the
     * change is recorded as {@code system}.
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
    public su.onno.history.HistoryActorResolver historyActorResolver(AccessService access,
                                                                    CurrentUserResolver currentUserResolver) {
        return () -> {
            String username = access.username(null);
            if (username == null || username.isBlank()) {
                return null;
            }
            String recordId = currentUserResolver.resolve(() -> username).recordId();
            return new su.onno.history.HistoryActor(username,
                    recordId == null || recordId.isBlank() ? null : java.util.UUID.fromString(recordId));
        };
    }

    @Bean
    public CurrentUserResolver currentUserResolver(IdentityBinding identityBinding,
                                                   MetadataRegistry registry, Jdbi jdbi) {
        return new CurrentUserResolver(identityBinding, registry, jdbi);
    }

    /**
     * The app's identity binding — which catalog record a login is — from an optional
     * {@code @Bean IdentityLink} (e.g. {@code IdentityLink.of(Employee.class, "email")}).
     */
    @Bean
    public IdentityBinding identityBinding(org.springframework.beans.factory.ObjectProvider<su.onno.identity.IdentityLink> link) {
        return new IdentityBinding(link.getIfAvailable());
    }

}
