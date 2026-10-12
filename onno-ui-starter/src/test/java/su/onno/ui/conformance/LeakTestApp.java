package su.onno.ui.conformance;

import su.onno.access.RecordAccessPolicy;
import su.onno.access.RecordScope;
import su.onno.access.Subject;
import su.onno.ui.ActionResult;
import su.onno.ui.ActionScope;
import su.onno.ui.ActionSpec;
import su.onno.ui.EntityConfigBuilder;
import su.onno.ui.EntityView;
import su.onno.ui.Layout;
import su.onno.ui.LayoutSpec;
import su.onno.ui.Page;
import su.onno.ui.PageBuilder;
import su.onno.ui.conformance.model.LkBuildJob;
import su.onno.ui.conformance.model.LkInstance;
import su.onno.ui.conformance.model.LkOwner;
import su.onno.ui.conformance.model.LkPlan;
import su.onno.ui.conformance.model.LkTenant;
import su.onno.ui.conformance.model.LkTenantFacts;
import su.onno.ui.conformance.model.LkUsage;

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
    Layout leakLayout() {
        return new Layout() {
            @Override
            public void configure(LayoutSpec layout) {
                layout.identity(LkOwner.class, "email");
                layout.section("Console")
                        .catalog(LkTenant.class)
                        .catalog(LkInstance.class)
                        .catalog(LkPlan.class)
                        .document(LkBuildJob.class);
            }
        };
    }

    @Bean
    Page leakDashboard() {
        return new Page() {
            @Override
            public String route() {
                return "/leak-dash";
            }

            @Override
            public void compose(PageBuilder b) {
                b.title("Console");
                b.widget("Tenants").type("count").catalog(LkTenant.class).config("metric", "count");
                b.widget("Jobs").type("count").document(LkBuildJob.class).config("metric", "count");
                b.list(LkInstance.class);
            }
        };
    }

    @Bean
    EntityView<LkTenant> tenantView() {
        return new EntityView<>() {
            @Override
            public Class<LkTenant> entity() {
                return LkTenant.class;
            }

            @Override
            public boolean comments() {
                return true;
            }

            @Override
            public void fields(EntityConfigBuilder<LkTenant> f) {
                f.relatedList("instances", LkInstance.class).via("tenant");
                f.relatedList("facts", LkTenantFacts.class).via("tenant");
            }

            @Override
            public void actions(ActionSpec a) {
                a.action("touch").scope(ActionScope.ROW).label("Touch")
                        .enabledWhen(row -> !"locked".equals(row.text("note")))
                        .handler(ctx -> {
                            TOUCHES.incrementAndGet();
                            return ActionResult.ok();
                        });
            }
        };
    }

    @Bean
    EntityView<LkOwner> ownerView() {
        return simpleView(LkOwner.class);
    }

    @Bean
    EntityView<LkInstance> instanceView() {
        return simpleView(LkInstance.class);
    }

    @Bean
    EntityView<LkPlan> planView() {
        return simpleView(LkPlan.class);
    }

    @Bean
    EntityView<LkBuildJob> buildJobView() {
        return new EntityView<>() {
            @Override
            public Class<LkBuildJob> entity() {
                return LkBuildJob.class;
            }

            @Override
            public boolean comments() {
                return true;
            }
        };
    }

    private static <E> EntityView<E> simpleView(Class<E> type) {
        return new EntityView<>() {
            @Override
            public Class<E> entity() {
                return type;
            }
        };
    }
}
