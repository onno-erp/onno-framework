package com.onec.ui;

import com.onec.metadata.AccumulationRegisterDescriptor;
import com.onec.metadata.CatalogDescriptor;
import com.onec.metadata.DocumentDescriptor;
import com.onec.model.AccumulationType;
import com.onec.ui.divkit.DashboardDivBuilder;
import com.onec.ui.divkit.DivCard;
import com.onec.ui.divkit.Palette;
import com.onec.ui.divkit.ShellLayoutBuilder;
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
 * Emits the server-rendered DivKit app, split so the client can render instantly
 * and load data lazily: {@code /shell} returns the chrome (top bar + nav, no data
 * — fast), and each content endpoint returns just that surface's card. The client
 * paints the shell immediately and streams content in beneath it behind a
 * skeleton. Everything is resolved for the caller's persona, intersected with
 * RBAC, in the requested theme/viewport — the same hooks a Flutter client uses.
 */
@RestController
@RequestMapping("/api/divkit")
public class DivKitController {

    private final UiLayout layout;
    private final UiLayoutResolver layoutResolver;
    private final UiProfileResolver profileResolver;
    private final UiAccessService access;
    private final CurrentUserResolver currentUserResolver;
    private final ResolvedMetadataService resolvedMetadata;
    private final UiViewResolver viewResolver;
    private final CatalogQueryService catalogQuery;
    private final DocumentQueryService documentQuery;
    private final RegisterQueryService registerQuery;

    public DivKitController(UiLayout layout,
                            UiLayoutResolver layoutResolver,
                            UiProfileResolver profileResolver,
                            UiAccessService access,
                            CurrentUserResolver currentUserResolver,
                            ResolvedMetadataService resolvedMetadata,
                            UiViewResolver viewResolver,
                            CatalogQueryService catalogQuery,
                            DocumentQueryService documentQuery,
                            RegisterQueryService registerQuery) {
        this.layout = layout;
        this.layoutResolver = layoutResolver;
        this.profileResolver = profileResolver;
        this.access = access;
        this.currentUserResolver = currentUserResolver;
        this.resolvedMetadata = resolvedMetadata;
        this.viewResolver = viewResolver;
        this.catalogQuery = catalogQuery;
        this.documentQuery = documentQuery;
        this.registerQuery = registerQuery;
    }

    // ----- chrome (fast, no entity data) -----

    @GetMapping("/shell")
    public Map<String, Object> shell(@RequestParam(required = false) String profile,
                                     @RequestParam(required = false) String viewport,
                                     @RequestParam(required = false) String theme,
                                     @RequestParam(required = false) String active,
                                     Principal principal) {
        boolean mobile = isMobile(viewport);
        Palette p = Palette.of(theme);
        UiLayout.Profile activeProfile = activeProfile(principal, profile);
        CurrentUserResolver.CurrentUser user = currentUserResolver.resolve(principal);

        List<ShellLayoutBuilder.NavSection> nav = navFor(principal, activeProfile, active);
        List<ShellLayoutBuilder.ProfileLink> profileLinks = profileLinksFor(principal);
        String brand = activeProfile.title() == null || activeProfile.title().isBlank()
                ? "OneC" : activeProfile.title();

        Map<String, Object> chrome = ShellLayoutBuilder.chrome(
                brand, user.displayName(), profileLinks, activeProfile.id(), nav, mobile, p);
        return DivCard.of("onec-shell", chrome);
    }

    // ----- content (per route; the slow, data-bearing part) -----

    @GetMapping("/home")
    public Map<String, Object> home(@RequestParam(required = false) String profile,
                                    @RequestParam(required = false) String viewport,
                                    @RequestParam(required = false) String theme,
                                    Principal principal) {
        boolean mobile = isMobile(viewport);
        Palette p = Palette.of(theme);
        UiLayout.Profile active = activeProfile(principal, profile);
        CurrentUserResolver.CurrentUser user = currentUserResolver.resolve(principal);

        List<DashboardDivBuilder.Widget> widgets = layoutResolver.resolveWidgets(active).stream()
                .filter(w -> access.canRead(principal, w.entityType(), w.entityName()))
                .map(w -> new DashboardDivBuilder.Widget(w.title(), w.widgetType()))
                .toList();
        String title = active.title() == null || active.title().isBlank() ? "Dashboard" : active.title();
        Map<String, Object> content = DashboardDivBuilder.build(
                title, "Welcome back, " + user.displayName(), widgets, mobile ? 1 : 2, p);
        return DivCard.of("onec-content", content);
    }

    @GetMapping("/catalogs/{name}")
    public Map<String, Object> catalogList(@PathVariable String name,
                                           @RequestParam(required = false) String theme,
                                           Principal principal) {
        CatalogDescriptor desc = catalogQuery.require(name);
        access.requireRead(principal, desc);
        Map<String, Object> content = SurfaceDivBuilder.catalogList(
                viewResolver.catalogList(desc), catalogQuery.list(desc), Palette.of(theme));
        return DivCard.of("onec-content", content);
    }

    @GetMapping("/documents/{name}")
    public Map<String, Object> documentList(@PathVariable String name,
                                            @RequestParam(required = false) String from,
                                            @RequestParam(required = false) String to,
                                            @RequestParam(required = false) String theme,
                                            Principal principal) {
        DocumentDescriptor desc = documentQuery.require(name);
        access.requireRead(principal, desc);
        Map<String, Object> content = SurfaceDivBuilder.documentList(
                viewResolver.documentList(desc), documentQuery.list(desc, from, to), name, Palette.of(theme));
        return DivCard.of("onec-content", content);
    }

    @GetMapping("/documents/{name}/{id}")
    public Map<String, Object> documentDetail(@PathVariable String name, @PathVariable UUID id,
                                              @RequestParam(required = false) String theme,
                                              Principal principal) {
        DocumentDescriptor desc = documentQuery.require(name);
        access.requireRead(principal, desc);
        Map<String, Object> content = SurfaceDivBuilder.documentDetail(
                resolvedMetadata.describeDocument(desc), documentQuery.get(desc, id), Palette.of(theme));
        return DivCard.of("onec-content", content);
    }

    @GetMapping("/registers/{name}")
    public Map<String, Object> registerReport(@PathVariable String name,
                                              @RequestParam(required = false) String from,
                                              @RequestParam(required = false) String to,
                                              @RequestParam(required = false) String theme,
                                              Principal principal) {
        AccumulationRegisterDescriptor desc = registerQuery.require(name);
        access.requireRead(principal, desc);
        List<Map<String, Object>> balances = desc.accumulationType() == AccumulationType.BALANCE
                ? registerQuery.balance(desc, Map.of())
                : null;
        Map<String, Object> content = SurfaceDivBuilder.registerReport(
                resolvedMetadata.describeRegister(desc), registerQuery.movements(desc, from, to), balances,
                Palette.of(theme));
        return DivCard.of("onec-content", content);
    }

    // ----- helpers -----

    private List<ShellLayoutBuilder.NavSection> navFor(Principal principal, UiLayout.Profile active,
                                                       String activePath) {
        List<ShellLayoutBuilder.NavSection> nav = new ArrayList<>();
        for (UiLayout.ResolvedSection section : layoutResolver.resolve(active)) {
            List<ShellLayoutBuilder.NavItem> items = section.items().stream()
                    .filter(item -> access.canRead(principal, item.type(), item.name()))
                    .map(item -> new ShellLayoutBuilder.NavItem(
                            item.name(), "onec:/" + item.href(), item.href().equals(activePath)))
                    .toList();
            if (!items.isEmpty()) {
                nav.add(new ShellLayoutBuilder.NavSection(section.name(), items));
            }
        }
        return nav;
    }

    private List<ShellLayoutBuilder.ProfileLink> profileLinksFor(Principal principal) {
        return profileResolver.switchable(layout, access.roles(principal)).stream()
                .map(pl -> new ShellLayoutBuilder.ProfileLink(pl.id(),
                        pl.title() == null || pl.title().isBlank() ? pl.id() : pl.title()))
                .toList();
    }

    private UiLayout.Profile activeProfile(Principal principal, String profileParam) {
        Set<String> roles = access.roles(principal);
        UiProfileResolver.Resolution resolution = profileResolver.resolve(layout, roles);
        Set<String> switchableIds = resolution.switchable().stream()
                .map(UiLayout.Profile::id)
                .collect(Collectors.toSet());
        return profileParam != null && switchableIds.contains(profileParam)
                ? profileResolver.byId(layout, profileParam)
                : resolution.profile();
    }

    private static boolean isMobile(String viewport) {
        return "mobile".equalsIgnoreCase(viewport);
    }
}
