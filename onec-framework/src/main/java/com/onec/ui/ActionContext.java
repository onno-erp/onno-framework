package com.onec.ui;

import java.util.UUID;

/**
 * What a custom action handler receives when invoked: the entity it ran on and (for row/detail
 * actions) the record's id. The handler does whatever it likes with the services its
 * {@link EntityView} bean injected — it's just a method on a Spring bean.
 *
 * @param kind {@code "catalogs"} or {@code "documents"}
 * @param name the entity's route name
 * @param id   the target record's id, or {@code null} for a toolbar (list-level) action
 * @param user the authenticated username, for the handler's own checks
 */
public record ActionContext(String kind, String name, UUID id, String user) {
}
