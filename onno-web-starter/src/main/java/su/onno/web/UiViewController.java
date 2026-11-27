package su.onno.web;

import su.onno.service.AccessService;
import su.onno.service.AccessSubjectResolver;
import su.onno.service.CatalogQueryService;
import su.onno.service.CommandService;
import su.onno.service.CurrentUserResolver;
import su.onno.service.DocumentQueryService;
import su.onno.service.EntityJsonRepresentation;
import su.onno.service.RegisterQueryService;
import su.onno.service.WebProperties;
import su.onno.metadata.AccumulationRegisterDescriptor;
import su.onno.metadata.AttributeDescriptor;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.DocumentDescriptor;
import su.onno.metadata.MetadataRegistry;
import su.onno.lifecycle.Postable;
import su.onno.web.comments.CommentProperties;
import su.onno.web.notifications.NotificationProperties;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The UI's server side: the business model the client builds every screen from, and the default
 * surfaces derived from it. Nothing here is authored UI — {@code /model} describes what exists and
 * what the caller may do; {@code /view/**} returns an entity's default list, record form or register
 * report (columns, filters, commands and per-record state resolved and RBAC-checked server-side).
 * Navigation, pages, dashboards and presentation live in the React client, which an app overrides
 * with its own components.
 *
 * <p>A view is data only — one of {@code {list}}, {@code {form}} or {@code {register}}, each the
 * descriptor of that surface. Page chrome (titles, padding, icons, the comments thread) is the
 * client's.</p>
 */
@RestController
@RequestMapping("/api/ui")
public class UiViewController {

    private final MetadataRegistry registry;
    private final AccessService access;
    private final CurrentUserResolver currentUserResolver;
    private final ResolvedMetadataService resolvedMetadata;
    private final DefaultListViews listViews;
    private final CatalogQueryService catalogQuery;
    private final DocumentQueryService documentQuery;
    private final RegisterQueryService registerQuery;
    private final CommandService commands;
    private final WebProperties webProperties;
    private final UiMessages messages;
    // Resolved per request (lazily): the comments module is wired after this controller, and is
    // absent entirely when onno.comments.enabled=false. getIfAvailable() yields null in that case.
    private final ObjectProvider<CommentProperties> commentProperties;
    private final ObjectProvider<NotificationProperties> notificationProperties;
    private AccessSubjectResolver subjects;
    // Extension modules' read-only record header actions (e.g. print forms); set by the auto-configuration.
    private List<RecordActionContributor> recordActionContributors = List.of();
    // Extension modules' read-only selection-toolbar downloads (e.g. batch print).
    private List<SelectionDownloadContributor> selectionDownloadContributors = List.of();
    private su.onno.history.RecordHistory recordHistory;
    private boolean periodCloseEnabled;

    public UiViewController(MetadataRegistry registry,
                            AccessService access,
                            CurrentUserResolver currentUserResolver,
                            ResolvedMetadataService resolvedMetadata,
                            DefaultListViews listViews,
                            CatalogQueryService catalogQuery,
                            DocumentQueryService documentQuery,
                            RegisterQueryService registerQuery,
                            CommandService commands,
                            WebProperties webProperties,
                            UiMessages messages,
                            ObjectProvider<CommentProperties> commentProperties,
                            ObjectProvider<NotificationProperties> notificationProperties) {
        this.registry = registry;
        this.access = access;
        this.currentUserResolver = currentUserResolver;
        this.resolvedMetadata = resolvedMetadata;
        this.listViews = listViews;
        this.catalogQuery = catalogQuery;
        this.documentQuery = documentQuery;
        this.registerQuery = registerQuery;
        this.commands = commands;
        this.webProperties = webProperties;
        this.messages = messages;
        this.commentProperties = commentProperties;
        this.notificationProperties = notificationProperties;
    }

    /** Wire the subject resolver (set by the auto-configuration). */
    public void setAccessSubjectResolver(AccessSubjectResolver subjects) {
        this.subjects = subjects;
    }

    /** Wire the extension selection-toolbar downloads (set by the auto-configuration). */
    public void setSelectionDownloadContributors(List<SelectionDownloadContributor> contributors) {
        this.selectionDownloadContributors = List.copyOf(contributors);
    }

    /**
     * Adds the contributed selection downloads to a list descriptor. Omitted when there are none so
     * descriptors of apps without such a module stay unchanged.
     */
    private Map<String, Object> withSelectionDownloads(Map<String, Object> descriptor, String kind, String name,
                                                       Class<?> type, Principal principal) {
        if (descriptor == null || selectionDownloadContributors.isEmpty()) return descriptor;
        var request = new SelectionDownloadContributor.SelectionRequest(kind, name, type, subject(principal));
        List<Map<String, Object>> downloads = new ArrayList<>();
        for (SelectionDownloadContributor contributor : selectionDownloadContributors) {
            for (SelectionDownloadContributor.SelectionDownload d : contributor.downloads(request)) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("key", d.key());
                item.put("title", d.title());
                item.put("url", d.url());
                downloads.add(item);
            }
        }
        if (!downloads.isEmpty()) descriptor.put("selectionDownloads", downloads);
        return descriptor;
    }

    /** Wire record change history (set by the auto-configuration; null when history is off). */
    public void setRecordHistory(su.onno.history.RecordHistory recordHistory) {
        this.recordHistory = recordHistory;
    }

    /** Whether the period-close service (and its {@code /api/period-close} endpoints) is wired. */
    public void setPeriodCloseEnabled(boolean enabled) {
        this.periodCloseEnabled = enabled;
    }

    /** Whether {@code type} opted into change history ({@code history = true}). */
    private boolean tracksHistory(Class<?> type) {
        return recordHistory != null && recordHistory.tracks(type);
    }

    /** Wire the extension record header actions (set by the auto-configuration). */
    public void setRecordActionContributors(List<RecordActionContributor> contributors) {
        this.recordActionContributors = List.copyOf(contributors);
    }

    private su.onno.access.AccessSubject subject(Principal principal) {
        return subjects != null
                ? subjects.resolve(principal)
                : su.onno.access.AccessSubject.user(access.username(principal), access.roles(principal));
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

    // ----- the model (what exists, and what the caller may do) -----

    /**
     * The business model as the caller sees it: every catalog, document and accumulation register
     * they may read — with its route, display title, bounded context and the caller's write/post
     * rights — plus the signed-in user. (Branding is {@code GET /api/branding}, served before
     * sign-in for the login screen, and nowhere else.) The client derives navigation (grouped
     * by context), default screens and permissions from this; no navigation is authored server-side.
     * {@code informationRegisters} lists the readable information registers (read through
     * {@code /api/information-registers}); {@code constants} the {@code @Constant} settings with
     * their value type, for administrators only (as {@code /api/settings} is).
     */
    @GetMapping("/model")
    public Map<String, Object> model(Principal principal) {
        List<Map<String, Object>> entities = new ArrayList<>();
        registry.allCatalogs().stream()
                .filter(d -> access.canRead(principal, d))
                .sorted(Comparator.comparing(CatalogDescriptor::logicalName))
                .forEach(d -> {
                    Map<String, Object> e = entity("catalogs", d.logicalName(), d.displayTitle(), d.context());
                    e.put("canWrite", access.canWrite(principal, d) && !webProperties.isReadOnly());
                    e.put("hierarchical", d.hierarchical());
                    e.put("history", tracksHistory(d.javaClass()));
                    entities.add(e);
                });
        registry.allDocuments().stream()
                .filter(d -> access.canRead(principal, d))
                .sorted(Comparator.comparing(DocumentDescriptor::logicalName))
                .forEach(d -> {
                    Map<String, Object> e = entity("documents", d.logicalName(), d.displayTitle(), d.context());
                    boolean canWrite = access.canWrite(principal, d) && !webProperties.isReadOnly();
                    e.put("canWrite", canWrite);
                    e.put("postable", Postable.class.isAssignableFrom(d.javaClass()));
                    e.put("canPost", canWrite && access.canPost(principal, d));
                    e.put("history", tracksHistory(d.javaClass()));
                    entities.add(e);
                });
        registry.allRegisters().stream()
                .filter(d -> access.canRead(principal, d))
                .sorted(Comparator.comparing(AccumulationRegisterDescriptor::logicalName))
                .forEach(d -> {
                    Map<String, Object> e = entity("registers", d.logicalName(), d.displayTitle(), d.context());
                    e.put("canWrite", false);
                    e.put("balance", "BALANCE".equals(String.valueOf(d.accumulationType())));
                    entities.add(e);
                });

        CurrentUserResolver.CurrentUser user = currentUserResolver.resolve(principal);
        Map<String, Object> me = new LinkedHashMap<>();
        me.put("username", access.username(principal));
        me.put("displayName", user.displayName());
        me.put("avatarUrl", Objects.requireNonNullElse(user.avatarUrl(), ""));
        me.put("roles", access.roles(principal));

        Map<String, Object> features = new LinkedHashMap<>();
        features.put("comments", commentsEnabled());
        features.put("notifications", notificationsEnabled());
        features.put("periodClose", periodCloseEnabled);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("user", me);
        out.put("features", features);
        out.put("entities", entities);
        out.put("informationRegisters", registry.allInformationRegisters().stream()
                .filter(d -> access.canRead(principal, d))
                .sorted(Comparator.comparing(su.onno.metadata.InformationRegisterDescriptor::logicalName))
                .map(d -> {
                    Map<String, Object> e = new LinkedHashMap<>();
                    e.put("logicalName", d.logicalName());
                    e.put("title", d.displayTitle());
                    e.put("context", d.context() == null ? "" : d.context());
                    e.put("periodic", d.periodicity() != su.onno.model.Periodicity.NONE);
                    return e;
                })
                .toList());
        // Settings (@Constant values) are administrator-only, like their endpoint.
        out.put("constants", !access.roles(principal).contains("ADMIN") ? List.of()
                : registry.allConstants().stream()
                .sorted(Comparator.comparing(su.onno.metadata.ConstantDescriptor::logicalName))
                .map(d -> Map.<String, Object>of("name", d.logicalName(), "type", d.valueType().getSimpleName()))
                .toList());
        return out;
    }

    /** One model entry: route kind + route name, display title and context. */
    private static Map<String, Object> entity(String kind, String logicalName, String title, String context) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("kind", kind);
        e.put("name", toSnakeCase(logicalName));
        e.put("logicalName", logicalName);
        e.put("title", title == null || title.isBlank() ? logicalName : title);
        e.put("context", context == null ? "" : context);
        e.put("route", "/" + kind + "/" + toSnakeCase(logicalName));
        return e;
    }

    // ----- default surfaces -----

    @GetMapping("/view/catalogs/{name}")
    public Map<String, Object> catalogList(@PathVariable String name,
                                           @RequestParam(required = false) String focus,
                                           Principal principal) {
        CatalogDescriptor desc = catalogQuery.require(name);
        access.requireRead(principal, desc);
        DefaultListView view = listViews.catalogList(desc);
        // Only the list descriptor is emitted; the client pages the data itself from /api/list
        // (so a 10k-row catalog never ships whole).
        boolean canWrite = access.canWrite(principal, desc);
        String newUrl = canWrite ? "onno://catalogs/" + name + "/new" : null;
        Map<String, Object> list = withSelectionDownloads(ViewDescriptors.listDescriptor(view, "catalogs", name, newUrl,
                canWrite, listCommands(desc.javaClass(), canWrite, principal),
                canWrite && commands.registry().hasDynamicCommands(desc.javaClass())), "catalogs", name, desc.javaClass(), principal);
        UUID focusId = focusId(focus);
        if (focusId != null) {
            list.put("focus", focusId.toString());
        }
        return Map.of("list", list);
    }

    @GetMapping("/view/catalogs/{name}/{id}")
    public Map<String, Object> catalogDetail(@PathVariable String name, @PathVariable UUID id,
                                             Principal principal) {
        return catalogRecord(name, id, principal, false);
    }

    @GetMapping("/record-refresh/{kind}/{name}/{id}")
    public Map<String, Object> recordRefresh(@PathVariable String kind, @PathVariable String name,
                                             @PathVariable UUID id,
                                             Principal principal) {
        return switch (kind) {
            case "catalogs" -> catalogRecord(name, id, principal, true);
            case "documents" -> documentRecord(name, id, principal, true);
            default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown record kind: " + kind);
        };
    }

    private Map<String, Object> catalogRecord(String name, UUID id, Principal principal,
                                               boolean refreshOnly) {
        CatalogDescriptor desc = catalogQuery.require(name);
        access.requireRead(principal, desc);
        su.onno.access.AccessSubject subject = subject(principal);
        // Load the record first: the record commands' per-record functions (availableWhen/title/…)
        // evaluate against it. Outside the viewer's record scope this is a 404, like a missing record.
        Map<String, Object> row = catalogQuery.get(subject, desc, id);
        // A record the viewer can read but not change (outside its write scope) renders read-only.
        boolean canWrite = access.canWrite(principal, desc) && !webProperties.isReadOnly()
                && catalogQuery.inScope(subject, desc, id, su.onno.access.AccessMode.WRITE);
        List<ViewDescriptors.RecordAction> actions = new ArrayList<>();
        // Read-only: offered to every viewer of the record, independent of canWrite (#410).
        addOpenInList(actions, "catalogs", name, id);
        addContributed(actions, "catalogs", name, desc.javaClass(), id, subject);
        if (canWrite) {
            actions.add(ViewDescriptors.RecordAction.builtIn("duplicate",
                    "onno://catalogs/" + name + "/" + id + "/duplicate"));
            actions.add(new ViewDescriptors.RecordAction("delete", null,
                    "onno://delete/catalogs/" + name + "/" + id, true));
        }
        // Record commands available on this record, with its per-record title/enabled state.
        List<Map<String, Object>> recordCommands = canWrite
                ? commands.recordCommands(desc.javaClass(), row, desc.attributes(), subject) : List.of();
        Map<String, Object> record = EntityJsonRepresentation.catalog(desc, row, EntityJsonRepresentation.Mode.LOGICAL);
        if (refreshOnly) {
            return Map.of("record", record, "actions", ViewDescriptors.actionItems(actions),
                    "commands", recordCommands, "readOnly", !canWrite);
        }
        return formView(formDescriptor("catalogs", name, id, resolvedMetadata.describeCatalog(desc), record,
                false, !canWrite, null, actions, recordCommands, true));
    }

    @GetMapping("/view/catalogs/{name}/new")
    public Map<String, Object> catalogNew(@PathVariable String name,
                                          @RequestParam Map<String, String> params, Principal principal) {
        CatalogDescriptor desc = catalogQuery.require(name);
        access.requireWrite(principal, desc);
        Map<String, Object> meta = resolvedMetadata.describeCatalog(desc);
        // Seed the New form from a fresh instance so domain field-initializer defaults pre-fill
        // (issue #181); blank for an entity with no usable no-arg constructor. Extra query params
        // overlay onto that seed as initial field values.
        return formView(formDescriptor("catalogs", name, null, meta, EntityJsonRepresentation.catalog(desc,
                        catalogQuery.newDraft(subject(principal), desc, params),
                        EntityJsonRepresentation.Mode.LOGICAL), false));
    }

    @GetMapping("/view/catalogs/{name}/{id}/duplicate")
    public Map<String, Object> catalogDuplicate(@PathVariable String name, @PathVariable UUID id,
                                                Principal principal) {
        CatalogDescriptor desc = catalogQuery.require(name);
        access.requireWrite(principal, desc);
        Map<String, Object> meta = resolvedMetadata.describeCatalog(desc);
        Map<String, Object> draft = duplicateDraft(catalogQuery.get(subject(principal), desc, id), desc.attributes());
        return formView(formDescriptor("catalogs", name, id, meta,
                EntityJsonRepresentation.catalog(desc, draft, EntityJsonRepresentation.Mode.LOGICAL), true));
    }

    @GetMapping("/view/documents/{name}")
    public Map<String, Object> documentList(@PathVariable String name,
                                            @RequestParam(required = false) String focus,
                                             Principal principal) {
        DocumentDescriptor desc = documentQuery.require(name);
        access.requireRead(principal, desc);
        DefaultListView view = listViews.documentList(desc);
        // Descriptor only — see catalogList. Date range is applied by the data feed.
        boolean canWrite = access.canWrite(principal, desc);
        String newUrl = canWrite ? "onno://documents/" + name + "/new" : null;
        Map<String, Object> list = withSelectionDownloads(ViewDescriptors.listDescriptor(view, "documents", name, newUrl,
                canWrite, listCommands(desc.javaClass(), canWrite, principal),
                canWrite && commands.registry().hasDynamicCommands(desc.javaClass())), "documents", name, desc.javaClass(), principal);
        UUID focusId = focusId(focus);
        if (focusId != null) {
            list.put("focus", focusId.toString());
        }
        return Map.of("list", list);
    }

    /**
     * The list descriptor a reference field's "Show all…" picker dialog (#411) opens: the target's
     * default list columns, search and filters. Picking is read-only, so the descriptor carries no
     * commands or New url.
     * {@code hierarchical} tells the dialog to show the folder tree and breadcrumbs.
     */
    @GetMapping("/pickers/{kind}/{name}")
    public Map<String, Object> pickerList(@PathVariable String kind, @PathVariable String name,
                                          Principal principal) {
        DefaultListView view;
        boolean hierarchical = false;
        String surfaceKind;
        if ("documents".equals(kind) || "document".equals(kind)) {
            DocumentDescriptor desc = documentQuery.require(name);
            access.requireRead(principal, desc);
            view = listViews.documentList(desc);
            surfaceKind = "documents";
        } else if ("catalogs".equals(kind) || "catalog".equals(kind)) {
            CatalogDescriptor desc = catalogQuery.require(name);
            access.requireRead(principal, desc);
            view = listViews.catalogList(desc);
            hierarchical = desc.hierarchical();
            surfaceKind = "catalogs";
        } else {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown picker kind: " + kind);
        }
        Map<String, Object> descriptor = ViewDescriptors.listDescriptor(
                view, surfaceKind, name, null, false, List.of());
        descriptor.put("hierarchical", hierarchical);
        return descriptor;
    }

    @GetMapping("/view/documents/{name}/{id}")
    public Map<String, Object> documentDetail(@PathVariable String name, @PathVariable UUID id,
                                              Principal principal) {
        return documentRecord(name, id, principal, false);
    }

    private Map<String, Object> documentRecord(String name, UUID id, Principal principal,
                                                boolean refreshOnly) {
        DocumentDescriptor desc = documentQuery.require(name);
        access.requireRead(principal, desc);
        su.onno.access.AccessSubject subject = subject(principal);
        Map<String, Object> row = documentQuery.get(subject, desc, id);
        // A document the viewer can read but not change (outside its write scope) renders read-only.
        boolean canWrite = access.canWrite(principal, desc) && !webProperties.isReadOnly()
                && documentQuery.inScope(subject, desc, id, su.onno.access.AccessMode.WRITE);
        boolean postable = Postable.class.isAssignableFrom(desc.javaClass());
        boolean posted = Boolean.TRUE.equals(row.get("_posted"));

        List<ViewDescriptors.RecordAction> actions = new ArrayList<>();
        // Posting rights can be narrower than write access (@AccessControl postRoles/unpostRoles, #546).
        boolean canPost = canWrite && postable && access.canPost(principal, desc);
        boolean canUnpost = canWrite && access.canUnpost(principal, desc);
        if (canUnpost && postable && posted) {
            actions.add(ViewDescriptors.RecordAction.builtIn("unpost", "onno://unpost/" + name + "/" + id));
        }
        // Read-only: offered to every viewer of the record, independent of canWrite (#410).
        addOpenInList(actions, "documents", name, id);
        addContributed(actions, "documents", name, desc.javaClass(), id, subject);
        if (canWrite) {
            actions.add(ViewDescriptors.RecordAction.builtIn("duplicate",
                    "onno://documents/" + name + "/" + id + "/duplicate"));
            // A posted document must be unposted explicitly before it can be deleted.
            if (!posted) {
                actions.add(new ViewDescriptors.RecordAction("delete", null,
                        "onno://delete/documents/" + name + "/" + id, true));
            }
        }
        // Record commands available on this record, with its per-record title/enabled state.
        List<Map<String, Object>> recordCommands = canWrite
                ? commands.recordCommands(desc.javaClass(), row, desc.attributes(), subject) : List.of();
        Map<String, Object> record = EntityJsonRepresentation.document(desc, row, EntityJsonRepresentation.Mode.LOGICAL);
        if (refreshOnly) {
            return Map.of("record", record, "actions", ViewDescriptors.actionItems(actions),
                    "commands", recordCommands, "readOnly", !canWrite, "canPost", canPost);
        }
        return formView(formDescriptor("documents", name, id, resolvedMetadata.describeDocument(desc), record,
                false, !canWrite, canPost, actions, recordCommands, true));
    }

    @GetMapping("/view/documents/{name}/new")
    public Map<String, Object> documentNew(@PathVariable String name,
                                           @RequestParam Map<String, String> params, Principal principal) {
        DocumentDescriptor desc = documentQuery.require(name);
        access.requireWrite(principal, desc);
        // Seed the New form from a fresh instance so domain field-initializer defaults pre-fill
        // (issue #181); blank for an entity with no usable no-arg constructor. Any extra query params
        // (a deep link like …/new?startsAt=…&room=<id>) overlay onto that seed as initial field values.
        return formView(formDescriptor("documents", name, null, resolvedMetadata.describeDocument(desc),
                EntityJsonRepresentation.document(desc,
                        documentQuery.newDraft(subject(principal), desc, params),
                        EntityJsonRepresentation.Mode.LOGICAL),
                false, false, canPostNew(principal, desc), List.of(), List.of(), false));
    }

    @GetMapping("/view/documents/{name}/{id}/duplicate")
    public Map<String, Object> documentDuplicate(@PathVariable String name, @PathVariable UUID id,
                                                 Principal principal) {
        DocumentDescriptor desc = documentQuery.require(name);
        access.requireWrite(principal, desc);
        Map<String, Object> draft = duplicateDraft(documentQuery.get(subject(principal), desc, id), desc.attributes());
        return formView(formDescriptor("documents", name, id, resolvedMetadata.describeDocument(desc),
                EntityJsonRepresentation.document(desc, draft, EntityJsonRepresentation.Mode.LOGICAL),
                true, false, canPostNew(principal, desc), List.of(), List.of(), false));
    }

    @GetMapping("/view/registers/{name}")
    public Map<String, Object> registerReport(@PathVariable String name,
                                                 Principal principal) {
        AccumulationRegisterDescriptor desc = registerQuery.require(name);
        access.requireRead(principal, desc);
        // Descriptor only (movements, plus a Balance tab for BALANCE registers); the client pages
        // the data itself from /api/list/registers/... so a packed register never ships its whole
        // movement log.
        Map<String, Object> register = ViewDescriptors.registerDescriptor(resolvedMetadata.describeRegister(desc),
                name, messages);
        return Map.of("register", register);
    }

    // ----- helpers -----

    /**
     * Whether the caller may post a new or duplicated document of this type — the Post button's
     * right (#546: posting rights can be narrower than write access). The REST layer rejects the
     * post regardless.
     */
    private boolean canPostNew(Principal principal, DocumentDescriptor desc) {
        return Postable.class.isAssignableFrom(desc.javaClass()) && !webProperties.isReadOnly()
                && access.canPost(principal, desc);
    }

    /**
     * The command descriptors (record and collection) a list surface offers: only for a caller who
     * may write the entity outside read-only mode, and only the commands whose roles it holds.
     * Execution goes to {@code /api/commands/{kind}/{name}/{key}}.
     */
    private List<Map<String, Object>> listCommands(Class<?> entity, boolean canWrite, Principal principal) {
        if (!canWrite || webProperties.isReadOnly()) {
            return List.of();
        }
        return commands.descriptors(commands.registry().staticCommands(entity), subject(principal));
    }

    /**
     * The "Open in list" header action (#410): opens the entity's list surface focused on this
     * record ({@code onno://{kind}/{name}?focus={id}}; the list feed returns the keyset window that
     * contains it). It needs only read access, which the detail surface has already enforced, so it
     * is offered regardless of write access or read-only mode.
     */
    private void addOpenInList(List<ViewDescriptors.RecordAction> actions, String kind, String name, UUID id) {
        actions.add(ViewDescriptors.RecordAction.builtIn("openInList", "onno://" + kind + "/" + name + "?focus=" + id));
    }

    /**
     * Read-only header actions contributed by extension modules ({@link RecordActionContributor},
     * e.g. print forms). Like "Open in list" they need only the read access the detail surface has
     * already enforced, so they are offered regardless of write access or read-only mode; each
     * contributor filters by the viewer itself.
     */
    private void addContributed(List<ViewDescriptors.RecordAction> actions, String kind, String name,
                                Class<?> type, UUID id, su.onno.access.AccessSubject subject) {
        var request = new RecordActionContributor.RecordActionRequest(kind, name, type, id, subject);
        for (RecordActionContributor contributor : recordActionContributors) {
            for (RecordActionContributor.RecordAction action : contributor.actions(request)) {
                actions.add(new ViewDescriptors.RecordAction(action.key(), action.title(), action.url(), false));
            }
        }
    }

    /**
     * The list route's {@code ?focus=<id>} ("Open in list", #410), or {@code null} when absent or
     * malformed. Only forwarded to the client: the list feed decides whether the record is visible.
     */
    private static UUID focusId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** A record form view: {@code {form: descriptor}}. */
    private static Map<String, Object> formView(Map<String, Object> form) {
        return Map.of("form", form);
    }

    /** A create form (New, or Duplicate when {@code duplicate}): no record actions or commands. */
    private Map<String, Object> formDescriptor(String kind, String name, UUID id, Map<String, Object> meta,
                                               Map<String, Object> row, boolean duplicate) {
        return formDescriptor(kind, name, id, meta, row, duplicate, false, null, List.of(), List.of(), false);
    }

    /**
     * The record form descriptor — field metadata, the record's initial values and what the caller
     * may do with it — as plain data every client renders natively. {@code id} is null for a New
     * form; with {@code duplicate} it names the source record (so the client knows which pane to
     * close) while the form still submits as a create. {@code readOnly} renders the form disabled;
     * {@code canPost} (documents) whether the caller may post it; {@code actions} the built-in record
     * actions and extension contributions; {@code commands} the record commands available on this
     * record (with its {@code title} and {@code enabled}). {@code refreshable} tells the client it
     * may re-read the record, its actions and commands from {@code /record-refresh} after a change.
     * Titles and button labels are the client's (model title + messages).
     */
    private Map<String, Object> formDescriptor(String kind, String name, UUID id, Map<String, Object> meta,
                                               Map<String, Object> row, boolean duplicate, boolean readOnly,
                                               Boolean canPost, List<ViewDescriptors.RecordAction> actions,
                                               List<Map<String, Object>> recordCommands, boolean refreshable) {
        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("kind", kind);
        descriptor.put("name", name);
        descriptor.put("id", id == null ? null : id.toString());
        descriptor.put("duplicate", duplicate);
        descriptor.put("readOnly", readOnly);
        if (canPost != null) {
            descriptor.put("canPost", canPost);
        }
        if (refreshable) {
            descriptor.put("refreshable", true);
        }
        if (!actions.isEmpty()) {
            descriptor.put("actions", ViewDescriptors.actionItems(actions));
        }
        if (!recordCommands.isEmpty()) {
            descriptor.put("commands", recordCommands);
        }
        descriptor.put("meta", meta);
        descriptor.put("initial", row);
        return descriptor;
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
        draft.keySet().removeIf(UiViewController::isIdentityColumn);
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

    /** Logical name → route segment ("Bank Accounts" → "bank_accounts"). */
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
}
