package su.onno.crm.ui;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import su.onno.crm.service.CrmInboxWorkspace;
import su.onno.ui.ListSpec;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Startup check for {@link CrmInboxView} beans: an unknown workspace key, or a filter key the
 * workspace bean does not declare (ListSpec filters and facets), fails startup. Facets are declared
 * statically on the workspace; options that change at runtime come from
 * {@link CrmInboxWorkspace.FacetOptions}, so every valid key is known here. Only views registered as
 * Spring beans are checked; a view constructed inline inside {@code Page.compose} is not visible here,
 * so declare inbox views as {@code @Bean} methods to get this check.
 */
public class CrmInboxViewValidator implements SmartInitializingSingleton {
    private final ObjectProvider<CrmInboxView> views;
    private final ObjectProvider<CrmInboxWorkspace> workspaces;

    public CrmInboxViewValidator(ObjectProvider<CrmInboxView> views, ObjectProvider<CrmInboxWorkspace> workspaces) {
        this.views = views;
        this.workspaces = workspaces;
    }

    @Override
    public void afterSingletonsInstantiated() {
        validate(views.orderedStream().toList(), workspaces.orderedStream().toList());
    }

    /** Throws {@link IllegalStateException} for an unknown workspace or an undeclared filter key. */
    static void validate(List<CrmInboxView> views, List<CrmInboxWorkspace> workspaces) {
        for (CrmInboxView view : views) {
            String key = view.workspaceKey();
            CrmInboxWorkspace workspace = workspaces.stream().filter(w -> w.key().equals(key)).findFirst()
                .orElseThrow(() -> new IllegalStateException("CrmInboxView references unknown inbox workspace '" + key
                    + "'; declared: " + workspaces.stream().map(CrmInboxWorkspace::key).toList()));
            // Keys only: evaluating facet option sources here would query host data during startup.
            Set<String> declared = java.util.stream.Stream.concat(
                    workspace.listSpec().filters().stream().map(ListSpec.Filter::field),
                    workspace.facets().stream().map(CrmInboxWorkspace.Facet::key))
                .collect(Collectors.toCollection(java.util.TreeSet::new));
            List<String> unknown = view.filterKeys().stream().filter(f -> !declared.contains(f)).toList();
            if (!unknown.isEmpty()) throw new IllegalStateException("CrmInboxView for workspace '" + key + "' lists filters " + unknown
                + " that the workspace does not declare (declared: " + declared
                + "). Declare them as ListSpec filters or CrmInboxWorkspace facets.");
        }
    }
}
