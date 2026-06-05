package com.onec.ui;

import com.onec.metadata.AccumulationRegisterDescriptor;
import com.onec.metadata.AttributeDescriptor;
import com.onec.metadata.CatalogDescriptor;
import com.onec.metadata.DashboardWidgetDescriptor;
import com.onec.metadata.DocumentDescriptor;
import com.onec.model.AccumulationType;
import com.onec.ui.divkit.DashboardDivBuilder;
import com.onec.ui.divkit.Div;
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
    private final UiActionResolver actionResolver;

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
                            RegisterQueryService registerQuery,
                            UiActionResolver actionResolver) {
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
        this.actionResolver = actionResolver;
    }

    // ----- chrome (fast, no entity data) -----

    @GetMapping("/shell")
    public Map<String, Object> shell(@RequestParam(required = false) String profile,
                                     @RequestParam(required = false) String viewport,
                                     @RequestParam(required = false) String theme,
                                     Principal principal) {
        Viewport vp = Viewport.parse(viewport);
        UiLayout vpLayout = layoutSet.forViewport(vp);
        NavStyle navStyle = navStyleFor(vp);
        Palette p = Palette.of(theme);
        // Profile is chosen by role (viewport-independent); take its viewport-specific
        // variant for nav so a mobile layout's curated sections win.
        String profileId = activeProfileId(principal, profile);
        UiLayout.Profile activeProfile = profileResolver.byId(vpLayout, profileId);
        CurrentUserResolver.CurrentUser user = currentUserResolver.resolve(principal);

        List<ShellLayoutBuilder.NavSection> nav = navFor(principal, activeProfile, vp);
        List<ShellLayoutBuilder.ProfileLink> profileLinks = profileLinksFor(principal);
        String brand = activeProfile.title() == null ? "" : activeProfile.title();

        // On a bottom tab bar the account (and any overflow destinations) live behind
        // the bar's "More" tab, which opens the /menu hub — so nothing is appended here.

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
        // As a standalone page (mobile), the account card carries its own padding +
        // border, so it just needs an outer margin to not sit flush against the edges —
        // the breathing room the web shell used to add around content.
        Div.margins(content, 16, 16, 16, 16);
        return DivCard.of("onec-content", content);
    }

    /**
     * The mobile "More" hub: the full navigation (grouped by section) plus the account
     * block. Reached from the bottom bar's "More" tab; resolved for the caller's
     * persona and RBAC exactly like the nav and shell.
     */
    @GetMapping("/menu")
    public Map<String, Object> menu(@RequestParam(required = false) String profile,
                                    @RequestParam(required = false) String viewport,
                                    @RequestParam(required = false) String theme,
                                    Principal principal) {
        Palette p = Palette.of(theme);
        Viewport vp = Viewport.parse(viewport);
        UiLayout vpLayout = layoutSet.forViewport(vp);
        String profileId = activeProfileId(principal, profile);
        UiLayout.Profile activeProfile = profileResolver.byId(vpLayout, profileId);
        CurrentUserResolver.CurrentUser user = currentUserResolver.resolve(principal);
        List<ShellLayoutBuilder.NavSection> nav = navFor(principal, activeProfile, vp);
        List<ShellLayoutBuilder.ProfileLink> profileLinks = profileLinksFor(principal);
        String brand = activeProfile.title() == null ? "" : activeProfile.title();
        Map<String, Object> content = ShellLayoutBuilder.menu(
                brand, nav, user.displayName(), profileLinks, activeProfile.id(), p);
        Div.margins(content, 16, 16, 16, 16);
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
            content = renderPage(pb, columns, p, principal, defaultTitle, greeting);
        } else {
            List<DashboardWidgetDescriptor> widgets = layoutResolver.resolveWidgets(active).stream()
                    .filter(w -> access.canRead(principal, w.entityType(), w.entityName()))
                    .toList();
            content = DashboardDivBuilder.build(defaultTitle, greeting, widgets, columns, this::widgetValue, p);
        }
        return DivCard.of("onec-content", content);
    }

    /**
     * The Settings surface — just another authored {@link Page}, like the dashboard. An app can
     * declare its own {@code Page} with route {@code "/settings"} to compose lists/widgets next to
     * (or instead of) the constant editor; otherwise the default page renders the constant editor.
     */
    @GetMapping("/settings")
    public Map<String, Object> settings(@RequestParam(required = false) String profile,
                                        @RequestParam(required = false) String viewport,
                                        @RequestParam(required = false) String theme,
                                        Principal principal) {
        Viewport vp = Viewport.parse(viewport);
        int columns = vp == Viewport.MOBILE ? 1 : 2;
        Palette p = Palette.of(theme);
        UiLayout.Profile active = activeProfile(principal, profile);
        Page page = pageResolver.resolve("/settings", active.id(), vp);
        PageBuilder pb = new PageBuilder();
        if (page != null) {
            page.compose(pb);
        } else {
            pb.title("Settings").subtitle("App-wide configuration.").constants();
        }
        return DivCard.of("onec-content",
                renderPage(pb, columns, p, principal, "Settings", "App-wide configuration."));
    }

    /** Render a composed page (header + access-filtered widget grid + freeform components). */
    private Map<String, Object> renderPage(PageBuilder pb, int columns, Palette p, Principal principal,
                                           String defaultTitle, String defaultSubtitle) {
        List<DashboardWidgetDescriptor> widgets = layoutResolver.resolveWidgetConfigs(pb.widgets()).stream()
                .filter(w -> access.canRead(principal, w.entityType(), w.entityName()))
                .toList();
        String title = pb.title() != null ? pb.title() : defaultTitle;
        String subtitle = pb.subtitle() != null ? pb.subtitle() : defaultSubtitle;
        return PageDivBuilder.build(title, subtitle, widgets, pb.components(), columns, this::widgetValue, p);
    }

    /**
     * Custom toolbar/row action descriptors for an entity's list surface (what the {@code onec-list}
     * island renders as buttons). The kind/name travel in each descriptor so the client can POST a
     * server action to {@code /api/actions/{kind}/{name}/{key}} or route a navigation directly.
     */
    private List<Map<String, Object>> listActions(Class<?> entity, String kind, String name) {
        List<Map<String, Object>> out = actionResolver.descriptors(entity,
                java.util.EnumSet.of(ActionScope.TOOLBAR, ActionScope.ROW));
        for (Map<String, Object> a : out) {
            a.put("kind", kind);
            a.put("name", name);
        }
        return out;
    }

    /**
     * DETAIL-scope custom actions as detail-header buttons. A server action routes through the
     * {@code onec://action/...} scheme (the client POSTs and applies the {@link ActionResult}); a
     * navigation action fills its {@code {id}} placeholder and routes directly. Custom actions sit
     * in the overflow menu so they never crowd the built-in Edit/Post/Delete buttons.
     */
    private List<SurfaceDivBuilder.HeaderAction> detailActions(Class<?> entity, String kind, String name, UUID id) {
        List<SurfaceDivBuilder.HeaderAction> out = new ArrayList<>();
        for (ActionSpec.Action a : actionResolver.forEntity(entity)) {
            if (a.scope() != ActionScope.DETAIL) {
                continue;
            }
            String url = a.isServer()
                    ? "onec://action/" + kind + "/" + name + "/" + a.key() + "/" + id
                    : a.navigateUrl().replace("{id}", id.toString());
            String icon = a.icon() == null || a.icon().isBlank() ? "zap" : a.icon();
            out.add(new SurfaceDivBuilder.HeaderAction(icon, a.label(), "normal", url, "menu"));
        }
        return out;
    }

    @GetMapping("/catalogs/{name}")
    public Map<String, Object> catalogList(@PathVariable String name,
                                           @RequestParam(required = false) String profile,
                                           @RequestParam(required = false) String theme,
                                           Principal principal) {
        CatalogDescriptor desc = catalogQuery.require(name);
        access.requireRead(principal, desc);
        String profileId = activeProfile(principal, profile).id();
        requireView(desc.javaClass(), profileId);
        ResolvedListView view = viewResolver.catalogList(desc, profileId);
        // The list is now the virtualized onec-list island: we emit only its descriptor; it
        // pages the data itself from /api/list (so a 10k-row catalog never ships whole).
        String newUrl = access.canWrite(principal, desc) ? "onec://catalogs/" + name + "/new" : null;
        return DivCard.of("onec-content",
                SurfaceDivBuilder.listSurface(view, "catalogs", name, newUrl,
                        listActions(desc.javaClass(), "catalogs", name),
                        actionResolver.inputDescriptors(desc.javaClass())));
    }

    @GetMapping("/catalogs/{name}/{id}")
    public Map<String, Object> catalogDetail(@PathVariable String name, @PathVariable UUID id,
                                             @RequestParam(required = false) String profile,
                                             @RequestParam(required = false) String theme,
                                             Principal principal) {
        CatalogDescriptor desc = catalogQuery.require(name);
        access.requireRead(principal, desc);
        requireView(desc.javaClass(), activeProfile(principal, profile).id());
        // Only offer edit/delete to users who may write the catalog; the REST
        // endpoints enforce it regardless.
        boolean canWrite = access.canWrite(principal, desc);
        // Catalogs keep Edit/Delete as inline (primary) buttons — no posting, no overflow.
        List<SurfaceDivBuilder.HeaderAction> actions = new ArrayList<>();
        if (canWrite) {
            actions.add(new SurfaceDivBuilder.HeaderAction("pencil", "Edit", "normal",
                    "onec://catalogs/" + name + "/" + id + "/edit", "primary"));
            actions.add(new SurfaceDivBuilder.HeaderAction("copy", "Duplicate", "normal",
                    "onec://catalogs/" + name + "/" + id + "/duplicate", "menu"));
            actions.add(new SurfaceDivBuilder.HeaderAction("trash-2", "Delete", "danger",
                    "onec://delete/catalogs/" + name + "/" + id, "primary"));
        }
        if (canWrite) {
            actions.addAll(detailActions(desc.javaClass(), "catalogs", name, id));
        }
        Map<String, Object> content = SurfaceDivBuilder.catalogDetail(
                resolvedMetadata.describeCatalog(desc), catalogQuery.get(desc, id), actions,
                Palette.of(theme));
        return DivCard.of("onec-content", content);
    }

    @GetMapping("/catalogs/{name}/new")
    public Map<String, Object> catalogNew(@PathVariable String name, Principal principal) {
        CatalogDescriptor desc = catalogQuery.require(name);
        access.requireWrite(principal, desc);
        Map<String, Object> meta = resolvedMetadata.describeCatalog(desc);
        return entityFormContent("catalogs", name, null, "New " + str(meta.get("name")), "Create", meta, null);
    }

    @GetMapping("/catalogs/{name}/{id}/edit")
    public Map<String, Object> catalogEdit(@PathVariable String name, @PathVariable UUID id, Principal principal) {
        CatalogDescriptor desc = catalogQuery.require(name);
        access.requireWrite(principal, desc);
        Map<String, Object> meta = resolvedMetadata.describeCatalog(desc);
        Map<String, Object> row = catalogQuery.get(desc, id);
        String label = str(row.get("_description"));
        if (label.isBlank()) {
            label = str(row.get("_code"));
        }
        return entityFormContent("catalogs", name, id, "Edit " + label, "Save", meta, row);
    }

    @GetMapping("/catalogs/{name}/{id}/duplicate")
    public Map<String, Object> catalogDuplicate(@PathVariable String name, @PathVariable UUID id, Principal principal) {
        CatalogDescriptor desc = catalogQuery.require(name);
        access.requireWrite(principal, desc);
        Map<String, Object> meta = resolvedMetadata.describeCatalog(desc);
        Map<String, Object> draft = duplicateDraft(catalogQuery.get(desc, id), desc.attributes());
        return entityFormContent("catalogs", name, id, "Duplicate " + str(meta.get("name")), "Create", meta, draft, true);
    }

    @GetMapping("/documents/{name}")
    public Map<String, Object> documentList(@PathVariable String name,
                                            @RequestParam(required = false) String profile,
                                            @RequestParam(required = false) String theme,
                                            Principal principal) {
        DocumentDescriptor desc = documentQuery.require(name);
        access.requireRead(principal, desc);
        String profileId = activeProfile(principal, profile).id();
        requireView(desc.javaClass(), profileId);
        ResolvedListView view = viewResolver.documentList(desc, profileId);
        // Virtualized onec-list island — see catalogList. Date range is applied by the data feed.
        String newUrl = access.canWrite(principal, desc) ? "onec://documents/" + name + "/new" : null;
        return DivCard.of("onec-content",
                SurfaceDivBuilder.listSurface(view, "documents", name, newUrl,
                        listActions(desc.javaClass(), "documents", name),
                        actionResolver.inputDescriptors(desc.javaClass())));
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
        Map<String, Object> meta = resolvedMetadata.describeDocument(desc);
        Map<String, Object> row = documentQuery.get(desc, id);
        // Posting actions: only for writable, postable documents. Post is offered in both
        // states (it re-posts when already posted, labelled accordingly); Unpost only once
        // posted. Placement (primary button / overflow ⋯ menu / hidden) comes from the
        // resolved metadata's "actions" map, which a view can override per action.
        boolean postable = Boolean.TRUE.equals(meta.get("postable"));
        boolean posted = Boolean.TRUE.equals(row.get("_posted"));
        @SuppressWarnings("unchecked")
        Map<String, Object> placement = (Map<String, Object>) meta.getOrDefault("actions", Map.of());

        List<SurfaceDivBuilder.HeaderAction> actions = new ArrayList<>();
        if (canWrite && postable) {
            actions.add(new SurfaceDivBuilder.HeaderAction("circle-check", posted ? "Re-post" : "Post",
                    "primary", "onec://post/" + name + "/" + id, str(placement.getOrDefault("post", "primary"))));
        }
        if (canWrite && postable && posted) {
            actions.add(new SurfaceDivBuilder.HeaderAction("rotate-ccw", "Unpost", "normal",
                    "onec://unpost/" + name + "/" + id, str(placement.getOrDefault("unpost", "menu"))));
        }
        if (canWrite) {
            actions.add(new SurfaceDivBuilder.HeaderAction("pencil", "Edit", "normal",
                    "onec://documents/" + name + "/" + id + "/edit", str(placement.getOrDefault("edit", "menu"))));
            actions.add(new SurfaceDivBuilder.HeaderAction("copy", "Duplicate", "normal",
                    "onec://documents/" + name + "/" + id + "/duplicate", str(placement.getOrDefault("duplicate", "menu"))));
            actions.add(new SurfaceDivBuilder.HeaderAction("trash-2", "Delete", "danger",
                    "onec://delete/documents/" + name + "/" + id, str(placement.getOrDefault("delete", "menu"))));
        }
        if (canWrite) {
            actions.addAll(detailActions(desc.javaClass(), "documents", name, id));
        }
        // "hidden" placement drops the action from the UI (it stays available via REST).
        actions.removeIf(a -> "hidden".equals(a.placement()));
        Map<String, Object> content = SurfaceDivBuilder.documentDetail(meta, row, actions, Palette.of(theme));
        return DivCard.of("onec-content", content);
    }

    @GetMapping("/documents/{name}/new")
    public Map<String, Object> documentNew(@PathVariable String name, Principal principal) {
        DocumentDescriptor desc = documentQuery.require(name);
        access.requireWrite(principal, desc);
        Map<String, Object> meta = resolvedMetadata.describeDocument(desc);
        return entityFormContent("documents", name, null, "New " + str(meta.get("name")), "Create", meta, null);
    }

    @GetMapping("/documents/{name}/{id}/edit")
    public Map<String, Object> documentEdit(@PathVariable String name, @PathVariable UUID id, Principal principal) {
        DocumentDescriptor desc = documentQuery.require(name);
        access.requireWrite(principal, desc);
        Map<String, Object> meta = resolvedMetadata.describeDocument(desc);
        Map<String, Object> row = documentQuery.get(desc, id);
        return entityFormContent("documents", name, id, "Edit " + str(meta.get("name")) + " " + str(row.get("_number")),
                "Save", meta, row);
    }

    @GetMapping("/documents/{name}/{id}/duplicate")
    public Map<String, Object> documentDuplicate(@PathVariable String name, @PathVariable UUID id, Principal principal) {
        DocumentDescriptor desc = documentQuery.require(name);
        access.requireWrite(principal, desc);
        Map<String, Object> meta = resolvedMetadata.describeDocument(desc);
        Map<String, Object> draft = duplicateDraft(documentQuery.get(desc, id), desc.attributes());
        return entityFormContent("documents", name, id, "Duplicate " + str(meta.get("name")), "Create", meta, draft, true);
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

    private List<ShellLayoutBuilder.NavSection> navFor(Principal principal, UiLayout.Profile active, Viewport vp) {
        String profileId = active.id();
        List<ShellLayoutBuilder.NavSection> nav = new ArrayList<>();
        // The home/dashboard route leads the nav — but only when there's actually a
        // dashboard to show (an authored Page for "/", or dashboard widgets in the
        // layout/profile). With neither, "/" is an empty surface, so we don't advertise
        // it in the nav rather than leading every app with a blank Dashboard entry.
        if (hasDashboard(active, vp)) {
            nav.add(new ShellLayoutBuilder.NavSection(null, null, List.of(
                    new ShellLayoutBuilder.NavItem("Dashboard", "onec://", "house", "/"))));
        }
        for (UiLayout.ResolvedSection section : layoutResolver.resolve(active)) {
            List<ShellLayoutBuilder.NavItem> items = section.items().stream()
                    .filter(item -> access.canRead(principal, item.type(), item.name()))
                    .filter(item -> isDeclared(item, profileId))
                    .map(item -> new ShellLayoutBuilder.NavItem(
                            // The display label is the title (falls back to name); the route
                            // still keys off the URL-safe href.
                            item.title(), "onec:/" + item.href(),
                            // An explicitly authored icon wins; otherwise fall back to the
                            // name heuristic (with the section icon as its final default).
                            item.icon() != null && !item.icon().isBlank()
                                    ? item.icon()
                                    : NavIcons.forItem(item.name(), item.type(), section.icon()),
                            item.href()))
                    .toList();
            if (!items.isEmpty()) {
                nav.add(new ShellLayoutBuilder.NavSection(section.name(), section.icon(), items));
            }
        }
        // App-wide settings (the @Constant values) are administrator-only, so the entry only
        // shows for admins; the /api/settings endpoints enforce it regardless.
        if (access.roles(principal).contains("ADMIN")) {
            nav.add(new ShellLayoutBuilder.NavSection(null, null, List.of(
                    new ShellLayoutBuilder.NavItem("Settings", "onec://settings", "settings", "/settings"))));
        }
        return nav;
    }

    /**
     * Whether the home route ("/") has a dashboard worth linking to — the same test
     * {@link #home} uses to decide what to render: an authored {@link Page} for "/"
     * wins, otherwise the layout/profile must contribute at least one dashboard widget.
     * Neither → "/" is an empty surface and the nav omits the Dashboard entry.
     */
    private boolean hasDashboard(UiLayout.Profile active, Viewport vp) {
        return pageResolver.resolve("/", active.id(), vp) != null
                || !layoutResolver.resolveWidgets(active).isEmpty();
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
     * Wraps a create/edit form as the portable {@code onec-form} custom component
     * (see {@link SurfaceDivBuilder#entityForm}). The descriptor is plain JSON — field
     * metadata + the record's initial values + where to submit — so every client (the
     * React web client today, a Flutter client later) renders its own native form from
     * the same contract. {@code row} is null for create.
     */
    private Map<String, Object> entityFormContent(String kind, String name, UUID id, String title,
                                                  String submitLabel, Map<String, Object> meta,
                                                  Map<String, Object> row) {
        return entityFormContent(kind, name, id, title, submitLabel, meta, row, false);
    }

    /**
     * {@code duplicate} marks a "clone" form: the {@code id} identifies the source record (so the
     * client knows which pane to close), but the form still submits as a create (POST) into a new
     * record. {@code initial} carries the source's attributes/line items minus its identity.
     */
    private Map<String, Object> entityFormContent(String kind, String name, UUID id, String title,
                                                  String submitLabel, Map<String, Object> meta,
                                                  Map<String, Object> row, boolean duplicate) {
        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("kind", kind);
        descriptor.put("name", name);
        descriptor.put("id", id == null ? null : id.toString());
        descriptor.put("title", title);
        descriptor.put("submitLabel", submitLabel);
        descriptor.put("duplicate", duplicate);
        descriptor.put("meta", meta);
        descriptor.put("initial", row);
        return DivCard.of("onec-content", SurfaceDivBuilder.entityForm(descriptor));
    }

    /**
     * Turns a loaded record into a create-form draft for "Duplicate": copies its attributes and
     * tabular-section rows, but drops the system identity/state so the user saves a brand-new
     * record. The new {@code _id}, {@code _number}/{@code _code}, {@code _posted=false} and
     * {@code _version} are assigned on save; {@code @Attribute(secret = true)} values are never
     * copied (the new record starts with them blank). Tabular row ids are ignored by the form and
     * re-generated on insert, so the line items clone with a fresh identity automatically.
     */
    private Map<String, Object> duplicateDraft(Map<String, Object> row, List<AttributeDescriptor> attributes) {
        Map<String, Object> draft = new LinkedHashMap<>(row);
        draft.keySet().removeIf(DivKitController::isIdentityColumn);
        for (AttributeDescriptor attr : attributes) {
            if (attr.secret()) {
                draft.remove(attr.columnName());
            }
        }
        return draft;
    }

    private static boolean isIdentityColumn(String column) {
        return switch (column) {
            case "_id", "_number", "_code", "_posted", "_version", "_deletion_mark" -> true;
            default -> false;
        };
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    /**
     * The preformatted big-number for a {@code count}/{@code metric} tile. {@code count}
     * tallies rows; {@code metric} aggregates a field ({@code sum|avg|min|max} via
     * {@code config("metric", ...)} + {@code config("metricField", ...)}). Both honour an
     * optional {@code config("filter", ...)} predicate, and registers aggregate a resource
     * server-side. The value is formatted here — currency- and locale-aware — so every
     * client renders the same string ("—" if the widget can't be resolved).
     */
    private String widgetValue(DashboardWidgetDescriptor w) {
        Map<String, String> cfg = w.extraConfig() == null ? Map.of() : w.extraConfig();
        String metric = cfg.getOrDefault("metric", "count");
        String field = cfg.get("metricField");
        String filter = cfg.get("filter");
        try {
            java.math.BigDecimal value = switch (w.entityType()) {
                case "catalog" -> catalogQuery.aggregate(catalogQuery.require(w.entityName()), metric, field, filter);
                case "document" -> documentQuery.aggregate(documentQuery.require(w.entityName()), metric, field, filter);
                // A register tile sums one resource (its turnover counterpart for a single number).
                case "register" -> registerQuery.total(registerQuery.require(w.entityName()), field, null, null, filter);
                default -> java.math.BigDecimal.ZERO;
            };
            return formatMetric(value, metric, cfg);
        } catch (RuntimeException notRenderable) {
            return "—";
        }
    }

    /**
     * Format a tile's value: a {@code config("currency", "EUR")} renders it as money;
     * otherwise {@code config("format", "integer|decimal")} (counts default to integer)
     * controls the fraction digits. Grouping/locale follow {@code config("locale", ...)}
     * (default {@code en-US}).
     */
    private static String formatMetric(java.math.BigDecimal value, String metric, Map<String, String> cfg) {
        java.util.Locale locale = cfg.containsKey("locale")
                ? java.util.Locale.forLanguageTag(cfg.get("locale")) : java.util.Locale.US;
        String format = cfg.get("format");
        boolean integer = "integer".equalsIgnoreCase(format)
                || (format == null && "count".equalsIgnoreCase(metric));

        // An explicit unit wins over a currency code: format a plain grouped number and
        // place the label on the configured side (suffix by default → "100 E"; prefix → "$100").
        String unit = cfg.get("unit");
        if (unit != null && !unit.isBlank()) {
            return attachUnit(plainNumber(value, locale, integer), unit.trim(), cfg.get("unitPosition"));
        }

        String currency = cfg.get("currency");
        if (currency != null && !currency.isBlank()) {
            try {
                java.text.NumberFormat nf = java.text.NumberFormat.getCurrencyInstance(locale);
                nf.setCurrency(java.util.Currency.getInstance(currency.toUpperCase()));
                return nf.format(value);
            } catch (IllegalArgumentException badCurrency) {
                // fall through to plain number formatting
            }
        }
        return plainNumber(value, locale, integer);
    }

    /** A plain grouped number honouring the integer/decimal fraction-digit policy. */
    private static String plainNumber(java.math.BigDecimal value, java.util.Locale locale, boolean integer) {
        java.text.NumberFormat nf = integer
                ? java.text.NumberFormat.getIntegerInstance(locale)
                : java.text.NumberFormat.getNumberInstance(locale);
        if (!integer) {
            nf.setMaximumFractionDigits(2);
        }
        return nf.format(value);
    }

    /** Place a unit label on either side of an already-formatted number. */
    private static String attachUnit(String number, String unit, String position) {
        return "prefix".equalsIgnoreCase(position) ? unit + number : number + " " + unit;
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
     * The nav style for a viewport. A non-desktop viewport with no layout of its own does
     * <em>not</em> inherit the universal/desktop nav (e.g. a sidebar) — a single-layout app
     * declaring {@code nav(SIDEBAR)} would otherwise render that sidebar on a phone, the
     * regression behind "resizing stopped changing the layout". Such a device falls back to a
     * bottom tab bar so the shell stays responsive without authoring a per-device layout.
     * Otherwise the targeting layout's explicit choice wins, else a per-device default.
     */
    private NavStyle navStyleFor(Viewport vp) {
        if (vp != Viewport.DESKTOP && !layoutSet.hasDedicated(vp)) {
            return NavStyle.BOTTOM_BAR;
        }
        NavStyle nav = layoutSet.forViewport(vp).shell().nav();
        if (nav != null) {
            return nav;
        }
        return vp == Viewport.MOBILE ? NavStyle.BOTTOM_BAR : NavStyle.TOPBAR;
    }
}
