package su.onno.web.relations;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import su.onno.access.RecordAccessPolicy;
import su.onno.access.RecordScope;
import su.onno.access.RoleGrants;
import su.onno.access.RoleKey;
import su.onno.web.relations.model.RlClinic;
import su.onno.web.relations.model.RlDoctor;
import su.onno.web.relations.model.RlClinicDoctor;

/** Fixture for join-entity relations saved with their record. */
@SpringBootConfiguration
@EnableAutoConfiguration
public class RelationTestApp {

    enum RlRole implements RoleKey { WRITER, LIMITED }

    /** Both roles write clinics, doctors and their links. */
    @Bean
    RoleGrants relationRoles() {
        return RoleGrants.of(RlRole.class)
                .role(RlRole.WRITER, r -> r.write(RlClinic.class, RlDoctor.class, RlClinicDoctor.class))
                .role(RlRole.LIMITED, r -> r.write(RlClinic.class, RlDoctor.class, RlClinicDoctor.class));
    }

    /** LIMITED users see (and may change) only the "visible" links. */
    @Bean
    RecordAccessPolicy visibleLinksOnly() {
        return RecordAccessPolicy.forCatalog(RlClinicDoctor.class).appliesTo("LIMITED")
                .read(RecordScope.eq("role", "visible"));
    }
}
