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
     */
    @Override
    public void actions(ActionSpec a) {
        // Toolbar (list-level) — no record id; runs across the whole catalog.
        a.action("audit").label("Audit").icon("clipboard-check").scope(ActionScope.TOOLBAR)
                .handler(ctx -> ActionResult.message("Audit started for all countries"));
        // Per-row — receives the row's id.
        a.action("ping").label("Ping").icon("activity").scope(ActionScope.ROW)
                .handler(ctx -> ActionResult.message("Pinged country " + ctx.id()));
        // Record detail — reloads the surface afterwards (the common "it changed data" case).
        a.action("touch").label("Touch").icon("hand").scope(ActionScope.DETAIL)
                .handler(ctx -> ActionResult.refresh("Touched " + ctx.id()));
    }
}
