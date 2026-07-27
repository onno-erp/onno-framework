package su.onno.ui;

import java.security.Principal;
import java.util.Map;
import java.util.UUID;

/**
 * Adds optional UI to a catalog/document detail surface without coupling the base UI starter to
 * the feature that owns it.
 */
@FunctionalInterface
public interface EntityDetailUiContributor {

    Map<String, Object> contribute(Context context, Map<String, Object> content);

    record Context(String kind, String name, UUID id, Class<?> entityType, Principal principal) {}
}
