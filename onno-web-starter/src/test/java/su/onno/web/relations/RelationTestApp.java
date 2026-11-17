package su.onno.web.relations;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import su.onno.access.RecordAccessPolicy;
import su.onno.access.RecordScope;
import su.onno.web.relations.model.RlClinicDoctor;

/** Fixture for join-entity relations saved with their record. */
@SpringBootConfiguration
@EnableAutoConfiguration
public class RelationTestApp {

    /** LIMITED users see (and may change) only the "visible" links. */
    @Bean
    RecordAccessPolicy visibleLinksOnly() {
        return RecordAccessPolicy.forCatalog(RlClinicDoctor.class).appliesTo("LIMITED")
                .read(RecordScope.eq("role", "visible"));
    }
}
