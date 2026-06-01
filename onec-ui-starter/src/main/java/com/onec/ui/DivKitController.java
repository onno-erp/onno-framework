package com.onec.ui;

import com.onec.metadata.AccumulationRegisterDescriptor;
import com.onec.metadata.CatalogDescriptor;
import com.onec.metadata.DashboardWidgetDescriptor;
import com.onec.metadata.DocumentDescriptor;
import com.onec.model.AccumulationType;
import com.onec.ui.divkit.AppShellBuilder;
import com.onec.ui.divkit.SurfaceDivBuilder;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Emits server-rendered DivKit cards. {@code GET /app} is the bootstrap a generic
 * client fetches on login: it returns the persona-tailored app shell for the
 * caller's resolved profile, intersected with RBAC. Profiles only curate what is
 * shown; access is still enforced per data endpoint elsewhere.
 */
@RestController
@RequestMapping("/api/ui/divkit")
public class DivKitController {

    private final UiLayout layout;
    private final UiLayoutResolver layoutResolver;
    private final UiProfileResolver profileResolver;
    private final UiAccessService access;
    private final CurrentUserResolver currentUserResolver;
    private final ResolvedMetadataService resolvedMetadata;
    private final CatalogQueryService catalogQuery;
    private final DocumentQueryService documentQuery;
    private final RegisterQueryService registerQuery;

    public DivKitController(UiLayout layout,
                            UiLayoutResolver layoutResolver,
                            UiProfileResolver profileResolver,
                            UiAccessService access,
                            CurrentUserResolver currentUserResolver,
                            ResolvedMetadataService resolvedMetadata,
                            CatalogQueryService catalogQuery,
                            DocumentQueryService documentQuery,
                            RegisterQueryService registerQuery) {
        this.layout = layout;
        this.layoutResolver = layoutResolver;
        this.profileResolver = profileResolver;
        this.access = access;
        this.currentUserResolver = currentUserResolver;
        this.resolvedMetadata = resolvedMetadata;
        this.catalogQuery = catalogQuery;
        this.documentQuery = documentQuery;
        this.registerQuery = registerQuery;
    }

    @GetMapping("/app")
    public Map<String, Object> app(@RequestParam(required = false) String profile, Principal principal) {
        Set<String> roles = access.roles(principal);
        UiProfileResolver.Resolution resolution = profileResolver.resolve(layout, roles);

        List<UiLayout.Profile> switchable = resolution.switchable();
        Set<String> switchableIds = switchable.stream()
                .map(UiLayout.Profile::id)
                .collect(Collectors.toSet());
        // Honor a client-requested profile only if the user is eligible for it.
        UiLayout.Profile active = profile != null && switchableIds.contains(profile)
                ? profileResolver.byId(layout, profile)
                : resolution.profile();

        List<AppShellBuilder.NavSection> nav = new ArrayList<>();
        for (UiLayout.ResolvedSection section : layoutResolver.resolve(active)) {
            List<AppShellBuilder.NavItem> items = section.items().stream()
                    .filter(item -> access.canRead(principal, item.type(), item.name()))
                    .map(item -> new AppShellBuilder.NavItem(item.name(), "onec:/" + item.href()))
                    .toList();
            if (!items.isEmpty()) {
                nav.add(new AppShellBuilder.NavSection(section.name(), items));
            }
        }

        List<String> home = layoutResolver.resolveWidgets(active).stream()
                .filter(w -> access.canRead(principal, w.entityType(), w.entityName()))
                .map(DashboardWidgetDescriptor::title)
                .toList();

        List<AppShellBuilder.ProfileLink> profileLinks = switchable.stream()
                .map(p -> new AppShellBuilder.ProfileLink(p.id(),
                        p.title() == null || p.title().isBlank() ? p.id() : p.title()))
                .toList();

        CurrentUserResolver.CurrentUser user = currentUserResolver.resolve(principal);
        String title = active.title() == null || active.title().isBlank() ? "Home" : active.title();
        String greeting = "Hi, " + user.displayName();

        return AppShellBuilder.build(title, active.theme(), greeting, active.id(),
                profileLinks, nav, home);
    }

    @GetMapping("/catalogs/{name}")
    public Map<String, Object> catalogList(@PathVariable String name, Principal principal) {
        CatalogDescriptor desc = catalogQuery.require(name);
        access.requireRead(principal, desc);
        return SurfaceDivBuilder.catalogList(resolvedMetadata.describeCatalog(desc), catalogQuery.list(desc));
    }

    @GetMapping("/documents/{name}")
    public Map<String, Object> documentList(@PathVariable String name,
                                            @RequestParam(required = false) String from,
                                            @RequestParam(required = false) String to,
                                            Principal principal) {
        DocumentDescriptor desc = documentQuery.require(name);
        access.requireRead(principal, desc);
        return SurfaceDivBuilder.documentList(resolvedMetadata.describeDocument(desc),
                documentQuery.list(desc, from, to), name);
    }

    @GetMapping("/documents/{name}/{id}")
    public Map<String, Object> documentDetail(@PathVariable String name, @PathVariable UUID id,
                                              Principal principal) {
        DocumentDescriptor desc = documentQuery.require(name);
        access.requireRead(principal, desc);
        return SurfaceDivBuilder.documentDetail(resolvedMetadata.describeDocument(desc),
                documentQuery.get(desc, id));
    }

    @GetMapping("/registers/{name}")
    public Map<String, Object> registerReport(@PathVariable String name,
                                              @RequestParam(required = false) String from,
                                              @RequestParam(required = false) String to,
                                              Principal principal) {
        AccumulationRegisterDescriptor desc = registerQuery.require(name);
        access.requireRead(principal, desc);
        List<Map<String, Object>> balances = desc.accumulationType() == AccumulationType.BALANCE
                ? registerQuery.balance(desc, Map.of())
                : null;
        return SurfaceDivBuilder.registerReport(resolvedMetadata.describeRegister(desc),
                registerQuery.movements(desc, from, to), balances);
    }
}
