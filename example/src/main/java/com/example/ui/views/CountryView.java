package com.example.ui.views;

import com.example.domain.catalogs.Country;
import com.onec.ui.ActionResult;
import com.onec.ui.ActionScope;
import com.onec.ui.ActionSpec;
import com.onec.ui.EntityConfigBuilder;
import com.onec.ui.EntityView;
import com.onec.ui.InputSpec;
import com.onec.ui.InputType;

import org.springframework.stereotype.Component;

/**
 * Declares the Country catalog as a visible surface and its field order. List
 * columns fall back to the auto-generated defaults.
 */
@Component
public class CountryView implements EntityView {

    @Override
    public Class<?> entity() {
        return Country.class;
    }

    @Override
    public void fields(EntityConfigBuilder f) {
        f.field("iso2").order(0)
                .field("name").order(1)
                .field("nationality").order(2);
    }

    /**
     * Demo custom action buttons across all three scopes. A real handler would inject and call
     * services on this bean (it's just a Spring component); these return a toast / refresh result.
     * The handlers here {@link #slowWork sleep} briefly to stand in for slow/async work — while
     * they run, the button shows a loading state (spinner + disabled).
     */
    @Override
    public void actions(ActionSpec a) {
        // Toolbar (list-level) — no record id; runs across the whole catalog. Reads the toolbar
        // inputs (declared below) off the ActionContext.
        a.action("audit").label("Audit").icon("clipboard-check").scope(ActionScope.TOOLBAR)
                .handler(ctx -> {
                    slowWork();
                    String region = ctx.input("region");
                    String asOf = ctx.input("asOf");
                    return ActionResult.message("Audit started"
                            + (region.isBlank() ? "" : " for " + region)
                            + (asOf.isBlank() ? "" : " as of " + asOf));
                });
        // Per-row — receives the row's id.
        a.action("ping").label("Ping").icon("activity").scope(ActionScope.ROW)
                .handler(ctx -> { slowWork(); return ActionResult.message("Pinged country " + ctx.id()); });
        // Record detail — reloads the surface afterwards (the common "it changed data" case).
        a.action("touch").label("Touch").icon("hand").scope(ActionScope.DETAIL)
                .handler(ctx -> { slowWork(); return ActionResult.refresh("Touched " + ctx.id()); });
    }

    /**
     * Demo toolbar inputs. These don't filter the list — their current values are handed to the
     * action handlers above via {@link com.onec.ui.ActionContext#input(String)} when a button runs.
     */
    @Override
    public void inputs(InputSpec in) {
        in.input("region").label("Region").type(InputType.SELECT)
                .options("Europe", "Asia", "Americas").placeholder("All regions");
        in.input("asOf").label("As of").type(InputType.DATE);
    }

    /** Stand-in for a slow/async handler (a network call, a report, a batch job) — ~1.2s. */
    private static void slowWork() {
        try {
            Thread.sleep(1200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
