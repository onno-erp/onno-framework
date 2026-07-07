package su.onno.ui;

import su.onno.metadata.AccumulationRegisterDescriptor;
import su.onno.metadata.AttributeDescriptor;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.DashboardWidgetDescriptor;
import su.onno.metadata.DocumentDescriptor;
import su.onno.model.AccumulationType;
import su.onno.ui.divkit.DashboardDivBuilder;
import su.onno.ui.divkit.Div;
import su.onno.ui.divkit.DivCard;
import su.onno.ui.divkit.PageDivBuilder;
import su.onno.ui.divkit.Palette;
import su.onno.ui.divkit.ShellLayoutBuilder;
import su.onno.ui.divkit.SurfaceDivBuilder;
import su.onno.ui.comments.CommentProperties;
import su.onno.ui.notifications.NotificationProperties;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
public class DivKitController implements DisposableBean {

    private final LayoutSet layoutSet;
    private final UiLayout layout;
    // The consumer's branding (app name, logo, palette overrides), authored on the
    // default layout's shell. Viewport-independent — every viewport shares one brand.
    private final BrandingConfig branding;
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
    private final RelatedListReader relatedLists;
    private final UiProperties uiProperties;
    // The resolved chrome strings (English defaults + onno.ui.messages overrides) for the
    // server-rendered DivKit chrome — login aside (that's LoginDivController's payload).
    private final UiMessages messages;
    // Resolved per request (lazily): the comments module is wired after this controller, and is
    // absent entirely when onno.comments.enabled=false. getIfAvailable() yields null in that case.
    private final ObjectProvider<CommentProperties> commentProperties;
    // Same lazy seam for notifications: absent when onno.notifications.enabled=false, in which
    // case the mobile menu skips its Notifications row (the client bell hides itself off the 404).
    private final ObjectProvider<NotificationProperties> notificationProperties;
    // Resolves a dashboard's count/metric tiles concurrently (one SQL aggregate each), so a wide
    // dashboard doesn't pay N sequential round-trips. Bounded by onno.ui.dashboard.widget-parallelism
    // to stay under the JDBC pool; null when parallelism == 1 (resolve inline on the request thread).
    private final ExecutorService widgetPool;

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
                            UiActionResolver actionResolver,
                            RelatedListReader relatedLists,
                            UiProperties uiProperties,
                            UiMessages messages,
                            ObjectProvider<CommentProperties> commentProperties,
                            ObjectProvider<NotificationProperties> notificationProperties) {
        this.layoutSet = layoutSet;
        // Base layout for viewport-independent concerns (profile resolution, branding).
        this.layout = layoutSet.forViewport(Viewport.DESKTOP);
        this.branding = layout.shell().branding();
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
        this.relatedLists = relatedLists;
        this.uiProperties = uiProperties;
        this.messages = messages;
        this.commentProperties = commentProperties;
        this.notificationProperties = notificationProperties;
        int parallelism = Math.max(1, uiProperties.getDashboard().getWidgetParallelism());
        this.widgetPool = parallelism == 1 ? null : Executors.newFixedThreadPool(parallelism, r -> {
            Thread t = new Thread(r, "onno-dashboard-widget");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public void destroy() {
        if (widgetPool != null) {
            widgetPool.shutdownNow();
        }
    }

    /** Whether the comments module is wired and enabled — decided per request (see the field). */
    private boolean commentsEnabled() {
        CommentProperties cp = commentProperties.getIfAvailable();
        return cp != null && cp.isEnabled();
    }

    /** Whether the notifications module is wired — decided per request (see the field). */
    private boolean notificationsEnabled() {
        return notificationProperties.getIfAvailable() != null;
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
        Palette p = palette(theme);
        // Profile is chosen by role (viewport-independent); take its viewport-specific
        // variant for nav so a mobile layout's curated sections win.
        String profileId = activeProfileId(principal, profile);
        UiLayout.Profile activeProfile = profileResolver.byId(vpLayout, profileId);
        CurrentUserResolver.CurrentUser user = currentUserResolver.resolve(principal);

        List<ShellLayoutBuilder.NavSection> nav = navFor(principal, activeProfile, vp);
        List<ShellLayoutBuilder.ProfileLink> profileLinks = profileLinksFor(principal);
        String brand = brandName(activeProfile);
        ShellLayoutBuilder.Logo logo = ShellLayoutBuilder.Logo.of(
                branding.logoFor(theme), branding.logoWidth(), branding.logoHeight());

        // On a bottom tab bar the account (and any overflow destinations) live behind
        // the bar's "More" tab, which opens the /menu hub — so nothing is appended here.

        // Two islands: the nav and the account card. The client places the nav per
        // NavStyle and tucks the account under it (desktop) or omits it (mobile,
        // where it's the /account page).
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("navStyle", navStyle.name().toLowerCase());
        // Where "/" should land: the dashboard if there is one, else the first real nav
        // item, so a dashboard-less app opens on a real screen instead of a phantom one.
        out.put("home", landingPath(nav));
        out.put("nav", DivCard.of("onno-nav",
                ShellLayoutBuilder.nav(brand, logo, nav, navStyle, vp == Viewport.TABLET, p, messages)));
        out.put("account", DivCard.of("onno-account",
                ShellLayoutBuilder.account(user.displayName(), user.avatarUrl(), profileLinks, activeProfile.id(), p, messages)));
        // A flat route-path → localized label map (e.g. "/catalogs/customers" → "Клиенты"), built
        // from the same nav the sidebar renders. The web client titles its workspace tabs from this
        // so a tab reads in the chrome language instead of the humanized URL segment ("Customers").
        Map<String, String> titles = new LinkedHashMap<>();
        for (ShellLayoutBuilder.NavSection section : nav) {
            for (ShellLayoutBuilder.NavItem item : section.items()) {
                titles.putIfAbsent(item.path(), item.label());
            }
        }
        out.put("titles", titles);
        return out;
    }

    @GetMapping("/account")
    public Map<String, Object> account(@RequestParam(required = false) String profile,
                                       @RequestParam(required = false) String theme,
                                       Principal principal) {
        Palette p = palette(theme);
        String profileId = activeProfileId(principal, profile);
        CurrentUserResolver.CurrentUser user = currentUserResolver.resolve(principal);
        List<ShellLayoutBuilder.ProfileLink> profileLinks = profileLinksFor(principal);
        Map<String, Object> content = ShellLayoutBuilder.account(
                user.displayName(), user.avatarUrl(), profileLinks, profileId, p, messages);
        // As a standalone page (mobile), the account card carries its own padding +
        // border, so it just needs an outer margin to not sit flush against the edges —
        // the breathing room the web shell used to add around content.
        Div.margins(content, 16, 16, 16, 16);
        return DivCard.of("onno-content", content);
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
        Palette p = palette(theme);
        Viewport vp = Viewport.parse(viewport);
        UiLayout vpLayout = layoutSet.forViewport(vp);
        String profileId = activeProfileId(principal, profile);
        UiLayout.Profile activeProfile = profileResolver.byId(vpLayout, profileId);
        CurrentUserResolver.CurrentUser user = currentUserResolver.resolve(principal);
        List<ShellLayoutBuilder.NavSection> nav = navFor(principal, activeProfile, vp);
        List<ShellLayoutBuilder.ProfileLink> profileLinks = profileLinksFor(principal);
        String brand = brandName(activeProfile);
        ShellLayoutBuilder.Logo logo = ShellLayoutBuilder.Logo.of(
                branding.logoFor(theme), branding.logoWidth(), branding.logoHeight());
        Map<String, Object> content = ShellLayoutBuilder.menu(
                brand, logo, nav, user.displayName(), user.avatarUrl(), profileLinks, activeProfile.id(),
                notificationsEnabled(), p, messages);
        Div.margins(content, 16, 16, 16, 16);
        return DivCard.of("onno-content", content);
    }

    // ----- content (per route; the slow, data-bearing part) -----

    @GetMapping("/home")
    public Map<String, Object> home(@RequestParam(required = false) String profile,
                                    @RequestParam(required = false) String viewport,
                                    @RequestParam(required = false) String theme,
                                    Principal principal) {
        Viewport vp = Viewport.parse(viewport);
        int columns = vp == Viewport.MOBILE ? 1 : 2;
        Palette p = palette(theme);
        UiLayout.Profile active = activeProfile(principal, profile);
        CurrentUserResolver.CurrentUser user = currentUserResolver.resolve(principal);
        String greeting = "Welcome back, " + user.displayName();
        String defaultTitle = active.title() == null || active.title().isBlank()
                ? messages.get("nav.dashboard") : active.title();

        // An authored Page for "/" takes over the home surface; otherwise fall back
        // to the widget grid resolved from the layout/profile. A viewport-specific
        // page wins so the dashboard can differ per device.
        Page page = pageResolver.resolve("/", active.id(), vp);
        Map<String, Object> content;
        if (page != null) {
            PageBuilder pb = new PageBuilder();
            page.compose(pb);
            content = renderPage(pb, "/", columns, p, principal, active.id(), defaultTitle, greeting);
        } else {
            List<DashboardWidgetDescriptor> widgets = layoutResolver.resolveWidgets(active).stream()
                    .filter(w -> access.canRead(principal, w.entityType(), w.entityName()))
                    .toList();
            // No authored "/" page and no (readable) widgets means the app has no dashboard.
            // Render a neutral, empty surface rather than a phantom "Dashboard" / "Welcome
            // back" / "Nothing here yet" card — the client lands the user on the first real
            // nav item (see #shell "home"), and this is only the surface for an app that
            // exposes nothing at all.
            Map<DashboardWidgetDescriptor, String> values = resolveWidgetValues(widgets);
            content = widgets.isEmpty()
                    ? DashboardDivBuilder.empty()
                    : DashboardDivBuilder.build(defaultTitle, greeting, widgets, columns,
                            w -> values.getOrDefault(w, "—"), widgetWrite(principal), p);
        }
        return DivCard.of("onno-content", content);
    }

    /**
     * The Settings surface — opt-in via {@code onno.ui.settings.enabled} (off by default). An app
     * can author its own {@code Page} at route {@code "/settings"} to compose lists/widgets next to
     * (or instead of) the constant editor, and that authored page renders regardless of the flag;
     * the built-in default page (the constant editor) is gated on the flag. With neither, the route
     * is {@code 404}.
     */
    @GetMapping("/settings")
    public Map<String, Object> settings(@RequestParam(required = false) String profile,
                                        @RequestParam(required = false) String viewport,
                                        @RequestParam(required = false) String theme,
                                        Principal principal) {
        Viewport vp = Viewport.parse(viewport);
        int columns = vp == Viewport.MOBILE ? 1 : 2;
        Palette p = palette(theme);
        UiLayout.Profile active = activeProfile(principal, profile);
        Page page = pageResolver.resolve("/settings", active.id(), vp);
        PageBuilder pb = new PageBuilder();
        if (page != null) {
            page.compose(pb);
        } else if (uiProperties.getSettings().isEnabled()) {
            pb.title(messages.get("settings.title")).subtitle(messages.get("settings.subtitle")).constants();
        } else {
            // Opt-in and not enabled, with no authored page → the surface doesn't exist.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Settings page is not enabled");
        }
        return DivCard.of("onno-content",
                renderPage(pb, "/settings", columns, p, principal, active.id(),
                        messages.get("settings.title"), messages.get("settings.subtitle")));
    }

    /**
     * Run a page action button's server handler — the обработка-style logic declared via
     * {@link PageBuilder#actions}. The button posts the page {@code route} and action {@code key};
     * we re-resolve and re-compose the page (compose is a pure spec build, like the GET render),
     * find the handler by key, and return its {@link ActionResult}. Page actions have no entity to
     * gate on, so we require an authenticated user and leave finer authorization to the handler.
     */
    @PostMapping("/page-action")
    public ActionResult pageAction(@RequestParam String route, @RequestParam String key,
                                   @RequestParam(required = false) String profile,
                                   @RequestParam(required = false) String viewport,
                                   @RequestBody(required = false) Map<String, Object> body,
                                   Principal principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in to run this action");
        }
        Viewport vp = Viewport.parse(viewport);
        UiLayout.Profile active = activeProfile(principal, profile);
        Page page = pageResolver.resolve(route, active.id(), vp);
        if (page == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No page for route: " + route);
        }
        PageBuilder pb = new PageBuilder();
        page.compose(pb);
        ActionSpec.Action action = pb.pageAction(key);
        if (action == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown action: " + key);
        }
        if (!action.isServer()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Action is navigation-only: " + key);
        }
        ActionContext ctx = new ActionContext("page", route, null, principal.getName(), inputValues(body));
        ActionResult result = action.handler().apply(ctx);
        return result != null ? result : ActionResult.ok();
    }

    /** Pull the action input values out of the request body ({@code {"inputs": {key: value}}}). */
    @SuppressWarnings("unchecked")
    private static Map<String, String> inputValues(Map<String, Object> body) {
        Map<String, String> out = new LinkedHashMap<>();
        if (body != null && body.get("inputs") instanceof Map<?, ?> raw) {
            raw.forEach((k, v) -> out.put(String.valueOf(k), v == null ? "" : String.valueOf(v)));
        }
        return out;
    }

    /** Render a composed page (header + access-filtered widget grid + freeform components). */
    private Map<String, Object> renderPage(PageBuilder pb, String route, int columns, Palette p, Principal principal,
                                           String profileId, String defaultTitle, String defaultSubtitle) {
        List<DashboardWidgetDescriptor> widgets = layoutResolver.resolveWidgetConfigs(pb.widgets()).stream()
                // An entity-less widget (e.g. the shared time-range picker) has no entity to gate on.
                .filter(w -> w.entityType() == null || access.canRead(principal, w.entityType(), w.entityName()))
                .toList();
        List<PageComponent> components = expandComponents(pb.components(), route, profileId, principal);
        String title = pb.title() != null ? pb.title() : defaultTitle;
        String subtitle = pb.subtitle() != null ? pb.subtitle() : defaultSubtitle;
        Map<DashboardWidgetDescriptor, String> values = resolveWidgetValues(widgets);
        return PageDivBuilder.build(title, subtitle, widgets, components, columns,
                w -> values.getOrDefault(w, "—"), widgetWrite(principal), p);
    }

    /**
     * Per-widget write access for the caller, stamped into each widget descriptor as
     * {@code canWrite} so interactive widgets (kanban drag, calendar reschedule) disable their
     * mutations for read-only viewers. Entity-less widgets (the time-range picker) write nothing.
     */
    private java.util.function.Function<DashboardWidgetDescriptor, Boolean> widgetWrite(Principal principal) {
        return w -> w.entityType() == null || w.entityName() == null
                || access.canWrite(principal, w.entityType(), w.entityName());
    }

    /**
     * Detail/form metadata is principal-agnostic ({@link ResolvedMetadataService}); overlay the
     * caller's write access onto its related-list panels so the client only offers add/remove
     * where the junction catalog is actually writable. Register-backed panels arrive readOnly
     * already; the generic REST layer enforces the junction's write roles regardless.
     */
    private Map<String, Object> withRelatedListAccess(Map<String, Object> meta, Principal principal) {
        if (meta.get("relatedLists") instanceof List<?> panels) {
            for (Object o : panels) {
                if (o instanceof Map<?, ?> raw) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> panel = (Map<String, Object>) raw;
                    if (!Boolean.TRUE.equals(panel.get("readOnly"))
                            && !access.canWrite(principal, "catalog", str(panel.get("joinCatalog")))) {
                        panel.put("readOnly", true);
                    }
                }
            }
        }
        return meta;
    }

    /**
     * Expand the renderer-agnostic page components into concrete custom blocks: an embedded list
     * ({@code PageBuilder.list}) becomes the full {@code onno-list} surface; an action section
     * ({@code PageBuilder.actions}) becomes an {@code onno-actions} block carrying the page route
     * its buttons post back to.
     */
    private List<PageComponent> expandComponents(List<PageComponent> in, String route, String profileId,
                                                 Principal principal) {
        List<PageComponent> out = new ArrayList<>();
        for (PageComponent c : in) {
            if (c.kind() == PageComponent.Kind.LIST) {
                Map<String, Object> descriptor = embeddedListDescriptor(c.entity(), profileId, principal);
                if (descriptor != null) {
                    // Embedded in a page (which already applies content padding) the list drops its
                    // own horizontal gutter so its table aligns with the sibling cards, full width.
                    descriptor.put("embedded", true);
                    out.add(PageComponent.custom("onno-list", Map.of("list", descriptor)));
                }
            } else if (c.kind() == PageComponent.Kind.ACTIONS) {
                Map<String, Object> payload = new LinkedHashMap<>(c.payload());
                payload.put("route", route);
                if (profileId != null && !profileId.isBlank()) {
                    payload.put("profile", profileId);
                }
                out.add(PageComponent.custom("onno-actions", payload));
            } else {
                out.add(c);
            }
        }
        return out;
    }

    /**
     * The full {@code onno-list} descriptor (toolbar, New, actions, inputs, click-to-open) for a
     * catalog/document class embedded in a page, or {@code null} if it isn't a readable entity.
     */
    private Map<String, Object> embeddedListDescriptor(Class<?> entity, String profileId, Principal principal) {
        if (entity == null) {
            return null;
        }
        CatalogDescriptor cd = catalogQuery.forClass(entity);
        if (cd != null) {
            if (!access.canRead(principal, cd)) {
                return null;
            }
            ResolvedListView view = viewResolver.catalogList(cd, profileId);
            String name = toSnakeCase(cd.logicalName());
            boolean canWrite = access.canWrite(principal, cd);
            String newUrl = canWrite ? "onno://catalogs/" + name + "/new" : null;
            return SurfaceDivBuilder.listDescriptor(view, "catalogs", name, newUrl, canWrite,
                    listActions(entity, "catalogs", name), actionResolver.inputDescriptors(entity));
        }
        DocumentDescriptor dd = documentQuery.forClass(entity);
        if (dd != null) {
            if (!access.canRead(principal, dd)) {
                return null;
            }
            ResolvedListView view = viewResolver.documentList(dd, profileId);
            String name = toSnakeCase(dd.logicalName());
            boolean canWrite = access.canWrite(principal, dd);
            String newUrl = canWrite ? "onno://documents/" + name + "/new" : null;
            return SurfaceDivBuilder.listDescriptor(view, "documents", name, newUrl, canWrite,
                    listActions(entity, "documents", name), actionResolver.inputDescriptors(entity));
        }
        return null;
    }

    /** Logical name → route segment (mirrors UiLayoutResolver: "Bank Accounts" → "bank_accounts"). */
    private static String toSnakeCase(String name) {
        String normalized = name.replace(" ", "");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (Character.isUpperCase(c) && i > 0) {
                sb.append('_');
            }
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    /**
     * Custom toolbar/row action descriptors for an entity's list surface (what the {@code onno-list}
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
     * {@code onno://action/...} scheme (the client POSTs and applies the {@link ActionResult}); a
     * navigation action fills its {@code {id}} placeholder and routes directly. Custom actions sit
     * in the overflow menu so they never crowd the built-in Edit/Post/Delete buttons.
     */
    /**
     * Custom DETAIL-scope action buttons for an entity's detail header. Each honors the same
     * placement override the built-in post/edit/delete actions do — {@code f.action(key).primary()}
     * surfaces it as a prominent inline button, {@code .inMenu()} (the default) tucks it into the
     * overflow ⋯ menu, {@code .hidden()} drops it (the caller removes hidden entries). Issue #183.
     */
    private List<SurfaceDivBuilder.HeaderAction> detailActions(Class<?> entity, String kind, String name, UUID id,
                                                               Map<String, String> placement) {
        List<SurfaceDivBuilder.HeaderAction> out = new ArrayList<>();
        for (ActionSpec.Action a : actionResolver.forEntity(entity)) {
            if (a.scope() != ActionScope.DETAIL) {
                continue;
            }
            String url = a.isServer()
                    ? "onno://action/" + kind + "/" + name + "/" + a.key() + "/" + id
                    : a.navigateUrl().replace("{id}", id.toString());
            String icon = a.icon() == null || a.icon().isBlank() ? "zap" : a.icon();
            // Default to the overflow menu (the prior, only behavior); a view can promote a key
            // workflow action to a primary button — given the brand "accent" tone — via .primary().
            String place = placement.getOrDefault(a.key(), "menu");
            String tone = "primary".equals(place) ? "accent" : "normal";
            out.add(new SurfaceDivBuilder.HeaderAction(icon, a.label(), tone, url, place,
                    UiActionResolver.formDescriptors(a)));
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
        // The list is now the virtualized onno-list island: we emit only its descriptor; it
        // pages the data itself from /api/list (so a 10k-row catalog never ships whole).
        boolean canWrite = access.canWrite(principal, desc);
        String newUrl = canWrite ? "onno://catalogs/" + name + "/new" : null;
        return DivCard.of("onno-content",
                SurfaceDivBuilder.listSurface(view, "catalogs", name, newUrl, canWrite,
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
            actions.add(new SurfaceDivBuilder.HeaderAction("pencil", messages.get("action.edit"), "accent",
                    "onno://catalogs/" + name + "/" + id + "/edit", "primary"));
            actions.add(new SurfaceDivBuilder.HeaderAction("copy", messages.get("action.duplicate"), "normal",
                    "onno://catalogs/" + name + "/" + id + "/duplicate", "menu"));
            actions.add(new SurfaceDivBuilder.HeaderAction("trash-2", messages.get("action.delete"), "danger",
                    "onno://delete/catalogs/" + name + "/" + id, "primary"));
        }
        if (canWrite) {
            // Custom DETAIL actions honor f.action(key).primary()/inMenu()/hidden() placement (#183).
            actions.addAll(detailActions(desc.javaClass(), "catalogs", name, id,
                    resolvedMetadata.actionOverrides(desc.javaClass())));
        }
        // A custom action set to .hidden() drops out of the UI (it stays available via REST).
        actions.removeIf(a -> "hidden".equals(a.placement()));
        Map<String, Object> meta = withRelatedListAccess(resolvedMetadata.describeCatalog(desc), principal);
        Map<String, Object> content = SurfaceDivBuilder.catalogDetail(
                meta, catalogQuery.get(desc, id),
                relatedLists.preloadForDetail(desc.javaClass(), id, principal), actions,
                palette(theme), messages);
        if (commentsEnabled() && viewResolver.commentsEnabled(desc.javaClass())) {
            content = SurfaceDivBuilder.withComments(content, "catalogs", name, id.toString());
        }
        return DivCard.of("onno-content", content);
    }

    @GetMapping("/catalogs/{name}/new")
    public Map<String, Object> catalogNew(@PathVariable String name, Principal principal) {
        CatalogDescriptor desc = catalogQuery.require(name);
        access.requireWrite(principal, desc);
        Map<String, Object> meta = withRelatedListAccess(resolvedMetadata.describeCatalog(desc), principal);
        // Seed the New form from a fresh instance so domain field-initializer defaults pre-fill
        // (issue #181); blank for an entity with no usable no-arg constructor.
        return entityFormContent("catalogs", name, null, "New " + str(meta.get("name")), "Create",
                meta, catalogQuery.newDraft(desc));
    }

    @GetMapping("/catalogs/{name}/{id}/edit")
    public Map<String, Object> catalogEdit(@PathVariable String name, @PathVariable UUID id, Principal principal) {
        CatalogDescriptor desc = catalogQuery.require(name);
        access.requireWrite(principal, desc);
        Map<String, Object> meta = withRelatedListAccess(resolvedMetadata.describeCatalog(desc), principal);
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
        Map<String, Object> meta = withRelatedListAccess(resolvedMetadata.describeCatalog(desc), principal);
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
        // Virtualized onno-list island — see catalogList. Date range is applied by the data feed.
        boolean canWrite = access.canWrite(principal, desc);
        String newUrl = canWrite ? "onno://documents/" + name + "/new" : null;
        return DivCard.of("onno-content",
                SurfaceDivBuilder.listSurface(view, "documents", name, newUrl, canWrite,
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
        Map<String, Object> meta = withRelatedListAccess(resolvedMetadata.describeDocument(desc), principal);
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
            actions.add(new SurfaceDivBuilder.HeaderAction("circle-check",
                    messages.get(posted ? "action.repost" : "action.post"),
                    "primary", "onno://post/" + name + "/" + id, str(placement.getOrDefault("post", "primary"))));
        }
        if (canWrite && postable && posted) {
            actions.add(new SurfaceDivBuilder.HeaderAction("rotate-ccw", messages.get("action.unpost"), "normal",
                    "onno://unpost/" + name + "/" + id, str(placement.getOrDefault("unpost", "menu"))));
        }
        if (canWrite) {
            actions.add(new SurfaceDivBuilder.HeaderAction("pencil", messages.get("action.edit"), "normal",
                    "onno://documents/" + name + "/" + id + "/edit", str(placement.getOrDefault("edit", "menu"))));
            actions.add(new SurfaceDivBuilder.HeaderAction("copy", messages.get("action.duplicate"), "normal",
                    "onno://documents/" + name + "/" + id + "/duplicate", str(placement.getOrDefault("duplicate", "menu"))));
            actions.add(new SurfaceDivBuilder.HeaderAction("trash-2", messages.get("action.delete"), "danger",
                    "onno://delete/documents/" + name + "/" + id, str(placement.getOrDefault("delete", "menu"))));
        }
        if (canWrite) {
            // Custom DETAIL actions honor f.action(key).primary()/inMenu()/hidden() placement (#183),
            // the same override map the built-in post/edit/delete actions read above.
            actions.addAll(detailActions(desc.javaClass(), "documents", name, id,
                    resolvedMetadata.actionOverrides(desc.javaClass())));
        }
        // "hidden" placement drops the action from the UI (it stays available via REST).
        actions.removeIf(a -> "hidden".equals(a.placement()));
        Map<String, Object> content = SurfaceDivBuilder.documentDetail(
                meta, row, relatedLists.preloadForDetail(desc.javaClass(), id, principal), actions,
                palette(theme), messages);
        if (commentsEnabled() && viewResolver.commentsEnabled(desc.javaClass())) {
            content = SurfaceDivBuilder.withComments(content, "documents", name, id.toString());
        }
        return DivCard.of("onno-content", content);
    }

    @GetMapping("/documents/{name}/new")
    public Map<String, Object> documentNew(@PathVariable String name, Principal principal) {
        DocumentDescriptor desc = documentQuery.require(name);
        access.requireWrite(principal, desc);
        Map<String, Object> meta = withRelatedListAccess(resolvedMetadata.describeDocument(desc), principal);
        // Seed the New form from a fresh instance so domain field-initializer defaults pre-fill
        // (issue #181); blank for an entity with no usable no-arg constructor.
        return entityFormContent("documents", name, null, "New " + str(meta.get("name")), "Create",
                meta, documentQuery.newDraft(desc));
    }

    @GetMapping("/documents/{name}/{id}/edit")
    public Map<String, Object> documentEdit(@PathVariable String name, @PathVariable UUID id, Principal principal) {
        DocumentDescriptor desc = documentQuery.require(name);
        access.requireWrite(principal, desc);
        Map<String, Object> meta = withRelatedListAccess(resolvedMetadata.describeDocument(desc), principal);
        Map<String, Object> row = documentQuery.get(desc, id);
        return entityFormContent("documents", name, id, "Edit " + str(meta.get("name")) + " " + str(row.get("_number")),
                "Save", meta, row);
    }

    @GetMapping("/documents/{name}/{id}/duplicate")
    public Map<String, Object> documentDuplicate(@PathVariable String name, @PathVariable UUID id, Principal principal) {
        DocumentDescriptor desc = documentQuery.require(name);
        access.requireWrite(principal, desc);
        Map<String, Object> meta = withRelatedListAccess(resolvedMetadata.describeDocument(desc), principal);
        Map<String, Object> draft = duplicateDraft(documentQuery.get(desc, id), desc.attributes());
        return entityFormContent("documents", name, id, "Duplicate " + str(meta.get("name")), "Create", meta, draft, true);
    }

    @GetMapping("/registers/{name}")
    public Map<String, Object> registerReport(@PathVariable String name,
                                              @RequestParam(required = false) String theme,
                                              Principal principal) {
        AccumulationRegisterDescriptor desc = registerQuery.require(name);
        access.requireRead(principal, desc);
        // The register is now a virtualized onno-list island (movements, plus a Balance tab for
        // BALANCE registers) — we emit only its descriptor; it pages the data itself from
        // /api/list/registers/... so a packed register never ships its whole movement log.
        return DivCard.of("onno-content",
                SurfaceDivBuilder.registerSurface(resolvedMetadata.describeRegister(desc), name,
                        palette(theme), messages));
    }

    // ----- helpers -----

    /** The palette for {@code theme}, with the consumer's brand color overrides merged in. */
    private Palette palette(String theme) {
        return Palette.of(theme, branding);
    }

    /**
     * The shell brand text: an explicitly configured {@code shell().brand(...)} app name
     * wins; otherwise fall back to the active profile's title (the historical source).
     */
    private String brandName(UiLayout.Profile activeProfile) {
        if (branding.hasAppName()) {
            return branding.appName();
        }
        return activeProfile.title() == null ? "" : activeProfile.title();
    }

    private List<ShellLayoutBuilder.NavSection> navFor(Principal principal, UiLayout.Profile active, Viewport vp) {
        String profileId = active.id();
        List<ShellLayoutBuilder.NavSection> nav = new ArrayList<>();
        // The home/dashboard route leads the nav — but only when there's actually a
        // dashboard to show (an authored Page for "/", or dashboard widgets in the
        // layout/profile). With neither, "/" is an empty surface, so we don't advertise
        // it in the nav rather than leading every app with a blank Dashboard entry.
        if (hasDashboard(active, vp)) {
            nav.add(new ShellLayoutBuilder.NavSection(null, null, List.of(
                    new ShellLayoutBuilder.NavItem(homeNavLabel(active, vp), "onno://", "house", "/"))));
        }
        for (UiLayout.ResolvedSection section : layoutResolver.resolve(active)) {
            // Nav is opt-in (a Layout bean places what the sidebar shows), but a section can still
            // be authored with HIDDEN placement to register/group its entities without surfacing
            // them: they stay registered and directly routable (onno://catalogs/<name>), just out
            // of the sidebar. Honor that here by skipping HIDDEN sections.
            if (UiLayout.Placement.HIDDEN.name().equalsIgnoreCase(section.placement())) {
                continue;
            }
            List<ShellLayoutBuilder.NavItem> items = section.items().stream()
                    .filter(item -> access.canRead(principal, item.type(), item.name()))
                    .filter(item -> isDeclared(item, profileId))
                    .map(item -> new ShellLayoutBuilder.NavItem(
                            // The display label is the title (falls back to name); the route
                            // still keys off the URL-safe href.
                            item.title(), "onno:/" + item.href(),
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
        // App-wide settings (the @Constant values) are opt-in (onno.ui.settings.enabled, off by
        // default) and administrator-only, so the entry shows only when enabled and for admins; the
        // /api/settings endpoints enforce the admin check regardless.
        if (uiProperties.getSettings().isEnabled() && access.roles(principal).contains("ADMIN")) {
            nav.add(new ShellLayoutBuilder.NavSection(null, null, List.of(
                    new ShellLayoutBuilder.NavItem(messages.get("nav.settings"), "onno://settings", "settings", "/settings"))));
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
     * The label for the home/dashboard nav item — and, via the shell's path→title map, its
     * open-tab chip (the client titles the "/" tab from this same nav label). An authored
     * {@code "/"} {@link Page} that sets a title wins (apps already localize the dashboard via
     * {@code b.title(...)}); otherwise the localizable {@code nav.dashboard} chrome string, which an
     * app can override through {@code onno.ui.messages}. Never the bare English literal, so a
     * non-English app's sidebar and tab read in its language.
     */
    private String homeNavLabel(UiLayout.Profile active, Viewport vp) {
        Page page = pageResolver.resolve("/", active.id(), vp);
        if (page != null) {
            PageBuilder pb = new PageBuilder();
            page.compose(pb);
            String title = pb.title();
            if (title != null && !title.isBlank()) {
                return title;
            }
        }
        return messages.get("nav.dashboard");
    }

    /**
     * Where opening the app should land. {@link #navFor} already leads the nav with the
     * Dashboard ("/") when {@link #hasDashboard}, so the first nav item's path is "/"
     * for a dashboard app and the first real screen otherwise — letting a dashboard-less
     * app open on a real surface instead of a phantom "Dashboard". Falls back to "/" (the
     * neutral, empty landing) only when the user can reach nothing at all.
     */
    private static String landingPath(List<ShellLayoutBuilder.NavSection> nav) {
        for (ShellLayoutBuilder.NavSection section : nav) {
            if (!section.items().isEmpty()) {
                return section.items().get(0).path();
            }
        }
        return "/";
    }

    /**
     * The view layer is the allowlist: a catalog/document appears only if an
     * {@link su.onno.ui.EntityView} declares it for this profile. Registers are
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
     * Wraps a create/edit form as the portable {@code onno-form} custom component
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
        return DivCard.of("onno-content", SurfaceDivBuilder.entityForm(descriptor));
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
    /** Mirrors {@code Widgets.NATIVE_CARD_TYPES}: the widget types that carry a server-resolved value. */
    private static final Set<String> VALUE_CARD_TYPES = Set.of("count", "metric");

    /**
     * Resolve the big-number text for every {@code count}/{@code metric} tile in one pass, keyed by
     * the descriptor instance. Identical tiles (same entity/metric/field/filter) share a single
     * query, and the distinct queries run concurrently on {@link #widgetPool} — so a dashboard of N
     * KPI cards costs one batch of parallel aggregates instead of N sequential ones. Non-card widgets
     * (chart/list/…) carry no value and are skipped (they fetch their own data client-side).
     */
    private Map<DashboardWidgetDescriptor, String> resolveWidgetValues(List<DashboardWidgetDescriptor> widgets) {
        // Group the value-bearing tiles by their resolution key so duplicates query once.
        Map<String, List<DashboardWidgetDescriptor>> byKey = new LinkedHashMap<>();
        for (DashboardWidgetDescriptor w : widgets) {
            if (w.widgetType() == null || VALUE_CARD_TYPES.contains(w.widgetType())) {
                byKey.computeIfAbsent(valueKey(w), k -> new ArrayList<>()).add(w);
            }
        }
        if (byKey.isEmpty()) {
            return Map.of();
        }
        // Resolve each distinct key once: inline when there's nothing to parallelize (or the pool is
        // disabled), otherwise fan the queries out and join. widgetValue swallows its own errors
        // (→ "—"), so a single failing tile never sinks the whole render.
        Map<String, String> resolved = new ConcurrentHashMap<>();
        if (widgetPool == null || byKey.size() == 1) {
            byKey.forEach((key, ws) -> resolved.put(key, widgetValue(ws.get(0))));
        } else {
            List<Future<?>> futures = new ArrayList<>(byKey.size());
            for (Map.Entry<String, List<DashboardWidgetDescriptor>> e : byKey.entrySet()) {
                futures.add(widgetPool.submit(() -> resolved.put(e.getKey(), widgetValue(e.getValue().get(0)))));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (ExecutionException ignored) {
                    // widgetValue never throws; defensive only.
                }
            }
        }
        Map<DashboardWidgetDescriptor, String> out = new IdentityHashMap<>();
        byKey.forEach((key, ws) -> {
            String v = resolved.getOrDefault(key, "—");
            for (DashboardWidgetDescriptor w : ws) {
                out.put(w, v);
            }
        });
        return out;
    }

    /** The de-dup key for a tile's resolved value: same key ⇒ same query ⇒ resolved once. */
    private static String valueKey(DashboardWidgetDescriptor w) {
        Map<String, String> cfg = w.extraConfig() == null ? Map.of() : w.extraConfig();
        return String.join(" ",
                String.valueOf(w.entityType()), String.valueOf(w.entityName()),
                cfg.getOrDefault("metric", "count"),
                String.valueOf(cfg.get("metricField")), String.valueOf(cfg.get("filter")));
    }

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
