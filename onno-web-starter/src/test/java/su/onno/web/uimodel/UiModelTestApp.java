package su.onno.web.uimodel;

import su.onno.access.CorePermissions;
import su.onno.access.RoleGrants;
import su.onno.access.RoleKey;
import su.onno.web.uimodel.model.UmCustomer;
import su.onno.web.uimodel.model.UmOrder;
import su.onno.web.uimodel.model.UmRate;
import su.onno.web.uimodel.model.UmSalary;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;

/** Fixture for {@code GET /api/ui/model}: entities with different read/write grants. */
@SpringBootConfiguration
@EnableAutoConfiguration
public class UiModelTestApp {

    public enum UmRole implements RoleKey { READER, WRITER, HR, ADMIN }

    @Bean
    RoleGrants uiModelRoles() {
        return RoleGrants.of(UmRole.class)
                .role(UmRole.READER, r -> r.read(UmCustomer.class, UmOrder.class, UmRate.class))
                .role(UmRole.WRITER, r -> r.write(UmCustomer.class, UmOrder.class).read(UmRate.class))
                .role(UmRole.HR, r -> r.write(UmSalary.class))
                .role(UmRole.ADMIN, r -> r.readAll().permit(CorePermissions.EDIT_SETTINGS));
    }
}
