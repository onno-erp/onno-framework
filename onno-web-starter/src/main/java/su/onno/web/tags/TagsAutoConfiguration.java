package su.onno.web.tags;

import su.onno.validation.CheckRegistry;
import org.jdbi.v3.core.Jdbi;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.Bean;
import su.onno.events.EntityChangePublisher;
import su.onno.events.EntityChangedEvent;
import su.onno.metadata.MetadataRegistry;
import su.onno.web.*;
import su.onno.service.*;

import java.util.HashMap;
import java.util.Map;

@AutoConfiguration(after=UiAutoConfiguration.class)
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnBean(CheckRegistry.class)
public class TagsAutoConfiguration {
    @Bean public org.springframework.context.ApplicationListener<org.springframework.context.PayloadApplicationEvent<su.onno.events.EntityChangedEvent>> tagCatalogChanges(
            TagService tags, org.springframework.beans.factory.ObjectProvider<TagCatalog> catalogs,
            org.springframework.context.ApplicationEventPublisher events) {
        return wrapped -> {
            var event=wrapped.getPayload();
            if (EntityChangedEvent.CATALOG.equals(event.entityType()) && !tags.ownsCatalogChange(event)
                    && catalogs.orderedStream().anyMatch(c -> TagService.sameCatalog(c.catalogName(), event.entityName()))) {
                events.publishEvent(new EntityChangedEvent(
                        EntityChangedEvent.UPDATED, "tag", event.entityName(), event.id(), null));
            }
        };
    }
    @Bean
    public TagService tagService(Jdbi jdbi, org.springframework.beans.factory.ObjectProvider<TagCatalog> catalogs,
                                 MetadataRegistry registry, EntityChangePublisher events) {
        Map<String, String> names = new HashMap<>();
        registry.allCatalogs().forEach(d -> names.put(TagService.scope("catalogs", d.logicalName()), d.logicalName()));
        registry.allDocuments().forEach(d -> names.put(TagService.scope("documents", d.logicalName()), d.logicalName()));
        return new TagService(jdbi, catalogs.orderedStream().toList(), events,
                scope -> names.getOrDefault(scope, TagService.nameFromScope(scope)));
    }
    @SuppressWarnings("deprecation")
    @Bean public TagController tagController(TagService tags,AccessService access,CatalogQueryService catalogs,DocumentQueryService documents, org.springframework.beans.factory.ObjectProvider<TagAccessPolicy> policies, org.springframework.beans.factory.ObjectProvider<su.onno.service.RecordAccess> recordAccess) { return new TagController(tags,access,catalogs,documents,policies.orderedStream().toList(),recordAccess.getIfAvailable()); }
}
