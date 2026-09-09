package com.example.crm.ui;

import org.springframework.stereotype.Component;
import com.example.crm.domain.Opportunity;
import su.onno.ui.Page;
import su.onno.ui.PageBuilder;

@Component
public class PipelinePage implements Page {

    @Override
    public String route() {
        return "/pipeline";
    }

    @Override
    public void compose(PageBuilder page) {
        page.title("Sales pipeline");
        page.subtitle("Qualify leads, track value, and keep the next action visible.");
        page.widget("Open opportunities").type("count").width("1/3")
                .catalog(Opportunity.class)
                .config("metric", "count")
                .config("filter", "stage != 'WON' AND stage != 'LOST'");
        page.widget("Pipeline value").type("metric").width("1/3")
                .catalog(Opportunity.class)
                .config("metric", "sum")
                .metricField(Opportunity::getAmount)
                .config("filter", "stage != 'LOST'");
        page.widget("Won").type("count").width("1/3")
                .catalog(Opportunity.class)
                .config("metric", "count")
                .config("filter", "stage = 'WON'");
        page.list(Opportunity.class);
    }
}
