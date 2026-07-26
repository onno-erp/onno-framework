package com.example.ui.pages;

import org.springframework.stereotype.Component;
import su.onno.ui.Page;
import su.onno.ui.PageBuilder;

/** Human work inbox backed by durable typed business-process tasks. */
@Component
public final class TasksPage implements Page {

    @Override
    public String route() {
        return "/tasks";
    }

    @Override
    public void compose(PageBuilder b) {
        b.title("My tasks");
        b.subtitle("Claim and complete work assigned to you or one of your roles.");
        b.widget("Process tasks")
                .type("tasks")
                .width("full")
                .hint("Tasks remain here across restarts until they are completed.");
    }
}
