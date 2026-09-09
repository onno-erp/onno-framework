package su.onno.ui.tags;

import org.jdbi.v3.core.Jdbi;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.Bean;
import su.onno.ui.*;

@AutoConfiguration(after=UiAutoConfiguration.class)
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnBean(FieldHintResolver.class)
public class TagsAutoConfiguration {
    @Bean public org.springframework.context.ApplicationListener<org.springframework.context.PayloadApplicationEvent<su.onno.events.EntityChangedEvent>> tagCatalogChanges(
            org.springframework.beans.factory.ObjectProvider<TagCatalog> catalogs, org.springframework.context.ApplicationEventPublisher events) {
        return wrapped -> {
            var event=wrapped.getPayload();
            if ("catalog".equals(event.entityType()) && catalogs.orderedStream().anyMatch(c -> c.catalogName().replace("_", "").equalsIgnoreCase(event.entityName().replace("_", ""))))
                events.publishEvent(new su.onno.events.EntityChangedEvent("updated","tag",event.entityName(),event.id(),null));
        };
    }
    @Bean public TagService tagService(Jdbi jdbi, org.springframework.beans.factory.ObjectProvider<TagCatalog> catalogs) { return new TagService(jdbi,catalogs.orderedStream().toList()); }
    @Bean public TagController tagController(TagService tags,UiAccessService access,CatalogQueryService catalogs,DocumentQueryService documents, org.springframework.beans.factory.ObjectProvider<TagAccessPolicy> policies, org.springframework.context.ApplicationEventPublisher events) { return new TagController(tags,access,catalogs,documents,policies.orderedStream().toList(),events); }
}
