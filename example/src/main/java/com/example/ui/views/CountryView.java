package com.example.ui.views;

import com.example.domain.catalogs.Country;
import com.onec.ui.ActionResult;
import com.onec.ui.ActionScope;
import com.onec.ui.ActionSpec;
import com.onec.ui.EntityConfigBuilder;
import com.onec.ui.EntityView;

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
        // Toolbar (list-level) — no record id; runs across the whole catalog.
        a.action("audit").label("Audit").icon("clipboard-check").scope(ActionScope.TOOLBAR)
                .handler(ctx -> { slowWork(); return ActionResult.message("Audit started for all countries"); });
        // Per-row — receives the row's id.
        a.action("ping").label("Ping").icon("activity").scope(ActionScope.ROW)
                .handler(ctx -> { slowWork(); return ActionResult.message("Pinged country " + ctx.id()); });
        // Record detail — reloads the surface afterwards (the common "it changed data" case).
        a.action("touch").label("Touch").icon("hand").scope(ActionScope.DETAIL)
                .handler(ctx -> { slowWork(); return ActionResult.refresh("Touched " + ctx.id()); });
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
