package com.onec.ui;

import com.onec.metadata.AccumulationRegisterDescriptor;
import com.onec.metadata.CatalogDescriptor;
import com.onec.metadata.DashboardWidgetDescriptor;
import com.onec.metadata.DocumentDescriptor;
import com.onec.model.AccumulationType;
import com.onec.ui.divkit.DashboardDivBuilder;
import com.onec.ui.divkit.DivCard;
import com.onec.ui.divkit.PageDivBuilder;
import com.onec.ui.divkit.Palette;
import com.onec.ui.divkit.ShellLayoutBuilder;
import com.onec.ui.divkit.SurfaceDivBuilder;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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

    private final LayoutSet layoutSet;
    private final UiLayout layout;
    private final UiLayoutResolver layoutResolver;
    private final UiProfileResolver profileResolver;
    private final UiAccessService access;
    private final CurrentUserResolver currentUserResolver;
    private final ResolvedMetadataService resolvedMetadata;
    private final UiViewResolver viewResolver;
    private final PageResolver pageResolver;
    private final CatalogQueryService catalogQuery;
    private final DocumentQueryService documentQuery;
    private final RegisterQueryService registerQuery;

    public DivKitController(LayoutSet layoutSet,
                            UiLayoutResolver layoutResolver,
                            UiProfileResolver profileResolver,
                            UiAccessService access,
                            CurrentUserResolver currentUserResolver,
                            ResolvedMetadataService resolvedMetadata,
                            UiViewResolver viewResolver,
                            PageResolver pageResolver,
                            CatalogQueryService catalogQuery,
                            DocumentQueryService documentQuery,
                            RegisterQueryService registerQuery) {
        this.layoutSet = layoutSet;
        // Base layout for viewport-independent concerns (profile resolution, branding).
        this.layout = layoutSet.forViewport(Viewport.DESKTOP);
        this.layoutResolver = layoutResolver;
        this.profileResolver = profileResolver;
        this.access = access;
        this.currentUserResolver = currentUserResolver;
        this.resolvedMetadata = resolvedMetadata;
        this.viewResolver = viewResolver;
        this.pageResolver = pageResolver;
        this.catalogQuery = catalogQuery;
        this.documentQuery = documentQuery;
        this.registerQuery = registerQuery;
    }

    // ----- chrome (fast, no entity data) -----

    @GetMapping("/shell")
    public Map<String, Object> shell(@RequestParam(required = false) String profile,
                                     @RequestParam(required = false) String viewport,
                                     @RequestParam(required = false) String theme,
                                     Principal principal) {
        Viewport vp = Viewport.parse(viewport);
        UiLayout vpLayout = layoutSet.forViewport(vp);
        NavStyle navStyle = navStyleFor(vpLayout, vp);
        Palette p = Palette.of(theme);
        // Profile is chosen by role (viewport-independent); take its viewport-specific
        // variant for nav so a mobile layout's curated sections win.
        String profileId = activeProfileId(principal, profile);
        UiLayout.Profile activeProfile = profileResolver.byId(vpLayout, profileId);
        CurrentUserResolver.CurrentUser user = currentUserResolver.resolve(principal);

        List<ShellLayoutBuilder.NavSection> nav = navFor(principal, activeProfile);
        List<ShellLayoutBuilder.ProfileLink> profileLinks = profileLinksFor(principal);
        String brand = activeProfile.title() == null ? "" : activeProfile.title();

        // On a bottom tab bar there's no room for chrome around the account, so add
        // a tab that opens the /account page instead.
        if (navStyle == NavStyle.BOTTOM_BAR) {
            nav = new ArrayList<>(nav);
            nav.add(new ShellLayoutBuilder.NavSection(null, null, List.of(
                    new ShellLayoutBuilder.NavItem("Account", "onec://account", "user", "/account"))));
        }

        // Two islands: the nav and the account card. The client places the nav per
        // NavStyle and tucks the account under it (desktop) or omits it (mobile,
        // where it's the /account page).
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("navStyle", navStyle.name().toLowerCase());
        out.put("nav", DivCard.of("onec-nav",
                ShellLayoutBuilder.nav(brand, nav, navStyle, vp == Viewport.TABLET, p)));
        out.put("account", DivCard.of("onec-account",
                ShellLayoutBuilder.account(user.displayName(), profileLinks, activeProfile.id(), p)));
        return out;
    }

    @GetMapping("/account")
    public Map<String, Object> account(@RequestParam(required = false) String profile,
                                       @RequestParam(required = false) String theme,
                                       Principal principal) {
        Palette p = Palette.of(theme);
        String profileId = activeProfileId(principal, profile);
        CurrentUserResolver.CurrentUser user = currentUserResolver.resolve(principal);
        List<ShellLayoutBuilder.ProfileLink> profileLinks = profileLinksFor(principal);
        Map<String, Object> content = ShellLayoutBuilder.account(
                user.displayName(), profileLinks, profileId, p);
        return DivCard.of("onec-content", content);
    }

    // ----- content (per route; the slow, data-bearing part) -----

    @GetMapping("/home")
    public Map<String, Object> home(@RequestParam(required = false) String profile,
                                    @RequestParam(required = false) String viewport,
                                    @RequestParam(required = false) String theme,
                                    Principal principal) {
        Viewport vp = Viewport.parse(viewport);
        int columns = vp == Viewport.MOBILE ? 1 : 2;
        Palette p = Palette.of(theme);
        UiLayout.Profile active = activeProfile(principal, profile);
        CurrentUserResolver.CurrentUser user = currentUserResolver.resolve(principal);
        String greeting = "Welcome back, " + user.displayName();
        String defaultTitle = active.title() == null || active.title().isBlank() ? "Dashboard" : active.title();

        // An authored Page for "/" takes over the home surface; otherwise fall back
        // to the widget grid resolved from the layout/profile. A viewport-specific
        // page wins so the dashboard can differ per device.
        Page page = pageResolver.resolve("/", active.id(), vp);
        Map<String, Object> content;
        if (page != null) {
            PageBuilder pb = new PageBuilder();
            page.compose(pb);
            List<DashboardWidgetDescriptor> widgets = layoutResolver.resolveWidgetConfigs(pb.widgets()).stream()
                    .filter(w -> access.canRead(principal, w.entityType(), w.entityName()))
                    .toList();
            String title = pb.title() != null ? pb.title() : defaultTitle;
            String subtitle = pb.subtitle() != null ? pb.subtitle() : greeting;
            content = PageDivBuilder.build(title, subtitle, widgets, pb.components(), columns, p);
        } else {
            List<DashboardWidgetDescriptor> widgets = layoutResolver.resolveWidgets(active).stream()
                    .filter(w -> access.canRead(principal, w.entityType(), w.entityName()))
                    .toList();
            content = DashboardDivBuilder.build(defaultTitle, greeting, widgets, columns, p);
        }
        return DivCard.of("onec-content", content);
    }

    @GetMapping("/catalogs/{name}")
    public Map<String, Object> catalogList(@PathVariable String name,
                                           @RequestParam(required = false) String profile,
                                           @RequestParam(required = false) String theme,
                                           @RequestParam(required = false) boolean delta,
                                           Principal principal) {
        CatalogDescriptor desc = catalogQuery.require(name);
        access.requireRead(principal, desc);
        String profileId = activeProfile(principal, profile).id();
        requireView(desc.javaClass(), profileId);
        ResolvedListView view = viewResolver.catalogList(desc, profileId);
        List<Map<String, Object>> rows = catalogQuery.list(desc);
        Palette p = Palette.of(theme);
        Map<String, Object> vars = Map.of("onec_count", rows.size());
        if (delta) {
            return DivCard.delta(List.of(
                    DivCard.change("onec-rows", SurfaceDivBuilder.catalogRows(view, rows, p))), vars);
        }
        return DivCard.ofVars("onec-content", SurfaceDivBuilder.catalogList(view, rows, p), vars);
    }

    @GetMapping("/documents/{name}")
    public Map<String, Object> documentList(@PathVariable String name,
                                            @RequestParam(required = false) String from,
                                            @RequestParam(required = false) String to,
                                            @RequestParam(required = false) String profile,
                                            @RequestParam(required = false) String theme,
                                            @RequestParam(required = false) boolean delta,
                                            Principal principal) {
        DocumentDescriptor desc = documentQuery.require(name);
        access.requireRead(principal, desc);
        String profileId = activeProfile(principal, profile).id();
        requireView(desc.javaClass(), profileId);
        ResolvedListView view = viewResolver.documentList(desc, profileId);
        List<Map<String, Object>> rows = documentQuery.list(desc, from, to);
        Palette p = Palette.of(theme);
        Map<String, Object> vars = Map.of("onec_count", rows.size());
        if (delta) {
            return DivCard.delta(List.of(
                    DivCard.change("onec-rows", SurfaceDivBuilder.documentRows(view, rows, name, p))), vars);
        }
        String newUrl = access.canWrite(principal, desc) ? "onec://documents/" + name + "/new" : null;
        return DivCard.ofVars("onec-content", SurfaceDivBuilder.documentList(view, rows, name, newUrl, p), vars);
    }

    @GetMapping("/documents/{name}/{id}")
    public Map<String, Object> documentDetail(@PathVariable String name, @PathVariable UUID id,
                                              @RequestParam(required = false) String theme,
                                              Principal principal) {
        DocumentDescriptor desc = documentQuery.require(name);
        access.requireRead(principal, desc);
        requireView(desc.javaClass(), activeProfile(principal, null).id());
        // Only offer edit/delete to users who may write the document; the REST
        // endpoints enforce it regardless.
        boolean canWrite = access.canWrite(principal, desc);
        String editUrl = canWrite ? "onec://documents/" + name + "/" + id + "/edit" : null;
        String deleteUrl = canWrite ? "onec://delete/documents/" + name + "/" + id : null;
        Map<String, Object> content = SurfaceDivBuilder.documentDetail(
                resolvedMetadata.describeDocument(desc), documentQuery.get(desc, id), editUrl, deleteUrl,
                Palette.of(theme));
        return DivCard.of("onec-content", content);
    }

    @GetMapping("/documents/{name}/new")
    public Map<String, Object> documentNew(@PathVariable String name,
                                           @RequestParam(required = false) String theme,
                                           Principal principal) {
        DocumentDescriptor desc = documentQuery.require(name);
        access.requireWrite(principal, desc);
        Map<String, Object> meta = resolvedMetadata.describeDocument(desc);
        Map<String, Object> form = SurfaceDivBuilder.documentForm(meta, refOptions(meta, principal),
                "onec://submit/documents/" + name, "Create", "New " + str(meta.get("name")), Palette.of(theme));
        return DivCard.ofVars("onec-content", form, SurfaceDivBuilder.formVars(meta, null));
    }

    @GetMapping("/documents/{name}/{id}/edit")
    public Map<String, Object> documentEdit(@PathVariable String name, @PathVariable UUID id,
                                            @RequestParam(required = false) String theme,
                                            Principal principal) {
        DocumentDescriptor desc = documentQuery.require(name);
        access.requireWrite(principal, desc);
        Map<String, Object> meta = resolvedMetadata.describeDocument(desc);
        Map<String, Object> row = documentQuery.get(desc, id);
        Map<String, Object> form = SurfaceDivBuilder.documentForm(meta, refOptions(meta, principal),
                "onec://submit/documents/" + name + "/" + id, "Save",
                "Edit " + str(meta.get("name")) + " " + str(row.get("_number")), Palette.of(theme));
        return DivCard.ofVars("onec-content", form, SurfaceDivBuilder.formVars(meta, row));
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

    private List<ShellLayoutBuilder.NavSection> navFor(Principal principal, UiLayout.Profile active) {
        String profileId = active.id();
        List<ShellLayoutBuilder.NavSection> nav = new ArrayList<>();
        for (UiLayout.ResolvedSection section : layoutResolver.resolve(active)) {
            List<ShellLayoutBuilder.NavItem> items = section.items().stream()
                    .filter(item -> access.canRead(principal, item.type(), item.name()))
                    .filter(item -> isDeclared(item, profileId))
                    .map(item -> new ShellLayoutBuilder.NavItem(
                            item.name(), "onec:/" + item.href(), section.icon(), item.href()))
                    .toList();
            if (!items.isEmpty()) {
                nav.add(new ShellLayoutBuilder.NavSection(section.name(), section.icon(), items));
            }
        }
        return nav;
    }

    /**
     * The view layer is the allowlist: a catalog/document appears only if an
     * {@link com.onec.ui.EntityView} declares it for this profile. Registers are
     * report surfaces with no EntityView yet, so they're exempt for now.
     */
    private boolean isDeclared(UiLayout.ResolvedItem item, String profileId) {
        if ("register".equals(item.type())) {
            return true;
        }
        return viewResolver.hasView(item.javaClass(), profileId);
    }

    /** Enforce the allowlist on a surface: no view for this profile → 404. */
    private void requireView(Class<?> entity, String profileId) {
        if (!viewResolver.hasView(entity, profileId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No view defined for this entity");
        }
    }

    /**
     * Candidate options for each visible-in-form reference field — the records the
     * user can pick. Keyed by field name; each option is {@code {value:_id, text:display}}.
     * Only catalog-targeted refs the caller can read are included.
     */
    @SuppressWarnings("unchecked")
    private Map<String, List<Map<String, Object>>> refOptions(Map<String, Object> meta, Principal principal) {
        Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>();
        for (Map<String, Object> a : (List<Map<String, Object>>) meta.getOrDefault("attributes", List.of())) {
            if (!Boolean.TRUE.equals(a.get("isRef")) || !Boolean.TRUE.equals(a.get("visibleInForm"))) {
                continue;
            }
            CatalogDescriptor target;
            try {
                target = catalogQuery.require(str(a.get("refTarget")));
            } catch (ResponseStatusException notACatalog) {
                continue;
            }
            if (!access.canRead(principal, target)) {
                continue;
            }
            List<Map<String, Object>> opts = new ArrayList<>();
            for (Map<String, Object> r : catalogQuery.list(target)) {
                String display = str(r.get("_description"));
                if (display.isBlank()) display = str(r.get("_code"));
                opts.add(Map.of("value", str(r.get("_id")), "text", display));
            }
            out.put(str(a.get("fieldName")), opts);
        }
        return out;
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
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

    /** The active profile id, resolved against the base layout (viewport-independent). */
    private String activeProfileId(Principal principal, String profileParam) {
        return activeProfile(principal, profileParam).id();
    }

    /**
     * The nav style for a viewport: the layout's explicit choice, else a sensible
     * per-device default (a bottom tab bar on mobile, a top bar otherwise).
     */
    private static NavStyle navStyleFor(UiLayout layout, Viewport vp) {
        NavStyle nav = layout.shell().nav();
        if (nav != null) {
            return nav;
        }
        return vp == Viewport.MOBILE ? NavStyle.BOTTOM_BAR : NavStyle.TOPBAR;
    }
}
