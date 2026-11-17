package su.onno.web.conformance;

import su.onno.access.RecordAccessPolicy;
import su.onno.access.RecordScope;
import su.onno.access.Subject;
import su.onno.command.CommandResult;
import su.onno.command.CommandSpec;
import su.onno.command.Commands;
import su.onno.identity.IdentityLink;
import su.onno.web.conformance.model.LkBuildJob;
import su.onno.web.conformance.model.LkInstance;
import su.onno.web.conformance.model.LkOwner;
import su.onno.web.conformance.model.LkTenant;
import su.onno.web.conformance.model.LkTenantFacts;
import su.onno.web.conformance.model.LkUsage;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * The record-policy conformance fixture: a customer console where a {@code CUSTOMER} sees only its
 * own owner record, tenants, the instances/build jobs/usage/facts of those tenants, and every plan.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
public class LeakTestApp {

    /** Counts handler runs of the {@code touch} action, so tests can assert one never ran. */
    public static final AtomicInteger TOUCHES = new AtomicInteger();

    @Bean
    RecordAccessPolicy ownersBySelf() {
        return RecordAccessPolicy.forCatalog(LkOwner.class).appliesTo("CUSTOMER")
                .read(RecordScope.eq("id", Subject.recordId()))
                .write(RecordScope.none());
    }

    @Bean
    RecordAccessPolicy tenantsByOwner() {
        return RecordAccessPolicy.forCatalog(LkTenant.class).appliesTo("CUSTOMER")
                .read(RecordScope.eq("owner", Subject.recordId()))
                .defaults(d -> d.set("owner", Subject.recordId()));
    }

    @Bean
    RecordAccessPolicy instancesByTenant() {
        return RecordAccessPolicy.of(LkInstance.class).appliesTo("CUSTOMER").read(RecordScope.via("tenant"));
    }

    @Bean
    RecordAccessPolicy buildJobsByTenant() {
        return RecordAccessPolicy.forDocument(LkBuildJob.class).appliesTo("CUSTOMER").read(RecordScope.via("tenant"));
    }

    @Bean
    RecordAccessPolicy usageByTenant() {
        return RecordAccessPolicy.forRegister(LkUsage.class).appliesTo("CUSTOMER").read(RecordScope.via("tenant"));
    }

    @Bean
    RecordAccessPolicy factsByTenant() {
        return RecordAccessPolicy.forRegister(LkTenantFacts.class).appliesTo("CUSTOMER")
                .read(RecordScope.via("tenant"));
    }

    @Bean
    IdentityLink leakIdentity() {
        return IdentityLink.of(LkOwner.class, "email");
    }

    @Bean
    Commands<LkTenant> tenantCommands() {
        return new Commands<>() {
            @Override
            public Class<LkTenant> entity() {
                return LkTenant.class;
            }

            @Override
            public void define(CommandSpec<LkTenant> c) {
                c.command("touch").title("Touch")
                        .enabledWhen(row -> !"locked".equals(row.text("note")))
                        .run(ctx -> {
                            TOUCHES.incrementAndGet();
                            return CommandResult.ok();
                        });
            }
        };
    }
}
