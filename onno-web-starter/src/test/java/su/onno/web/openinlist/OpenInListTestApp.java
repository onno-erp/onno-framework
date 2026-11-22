package su.onno.web.openinlist;

import su.onno.access.RoleGrants;
import su.onno.access.RoleKey;
import su.onno.web.openinlist.model.OlEvent;
import su.onno.web.openinlist.model.OlNote;
import su.onno.web.openinlist.model.OlPaged;
import su.onno.web.openinlist.model.OlPinned;
import su.onno.web.openinlist.model.OlTask;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Fixture for the "Open in list" header action (#410). Every entity in the model package gets its
 * default surface; READER reads every entity and WRITER also writes them.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
public class OpenInListTestApp {

    enum OlRole implements RoleKey { READER, WRITER }

    @Bean
    RoleGrants openInListRoles() {
        return RoleGrants.of(OlRole.class)
                .role(OlRole.READER, r -> r.read(OlEvent.class, OlNote.class, OlPaged.class, OlPinned.class, OlTask.class))
                .role(OlRole.WRITER, r -> r.write(OlEvent.class, OlNote.class, OlPaged.class, OlPinned.class, OlTask.class));
    }
}
