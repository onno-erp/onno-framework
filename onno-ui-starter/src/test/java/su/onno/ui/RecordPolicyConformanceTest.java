package su.onno.ui;

import su.onno.access.AccessSubject;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.DocumentDescriptor;
import su.onno.metadata.InformationRegisterDescriptor;
import su.onno.metadata.MetadataRegistry;
import su.onno.repository.InformationRegisterPersistence;
import su.onno.types.Ref;
import su.onno.ui.comments.CommentService;
import su.onno.ui.conformance.LeakTestApp;
import su.onno.ui.conformance.model.LkTenant;
import su.onno.ui.conformance.model.LkTenantFacts;
import su.onno.ui.notifications.NotificationRequest;
import su.onno.ui.notifications.NotificationService;
import su.onno.ui.presence.PresenceRegistry;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * The record-policy conformance suite (design §7): one fixture app with {@code CUSTOMER} policies on
 * a catalog (directly), a hierarchical catalog and a document with lines (through a ref), a balance
 * register and an information register (by dimension). Two owners are seeded; every record of owner
 * B carries the sentinel {@code LEAK-B} in its code/description/note. Every endpoint is then driven
 * as owner A, and
 *
 * <ul>
 *   <li>no response body may contain a {@code LEAK-B} sentinel or any of B's record ids;</li>
 *   <li>reads of B's records are 404, writes touching them 404/422;</li>
 *   <li>every route registered in the context must be classified below, and every route classified
 *       {@link Route#SCOPED} must actually have been exercised — a new endpoint without a
 *       classification fails the build (the coverage guard).</li>
 * </ul>
 */
@SpringBootTest(classes = LeakTestApp.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:record_policy_conformance;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "onno.scan-packages=su.onno.ui.conformance.model",
        "onno.access.records.log-denials=true",
        "onno.ui.locale=en",
        "spring.main.banner-mode=off"
})
class RecordPolicyConformanceTest {

    /** How a route relates to record data. */
    enum Route {
        /** Returns or changes catalog/document/register records: must be exercised by the leak test. */
        SCOPED,
        /** Serves no entity record data (chrome, config, the caller's own process/notification inbox). */
        NO_ENTITY_DATA,
        /** Gated to ADMIN or to entities a CUSTOMER cannot read in this fixture. */
        ADMIN_ONLY,
        /** Record-scoped, verified by a dedicated unit-level check rather than over HTTP (SSE stream). */
        SCOPED_ELSEWHERE
    }

    /**
     * Every route of the UI starter, classified. A route the context registers that is missing here
     * fails {@link #everyRouteIsClassified()}.
     */
    static final Map<String, Route> ROUTES = new LinkedHashMap<>();

    static {
        // Generic records REST
        for (String r : List.of(
                "GET /api/catalogs/{name}/children", "GET /api/catalogs/{name}/tree",
                "GET /api/catalogs/{name}/{id}", "GET /api/catalogs/{name}/{id}/related/{relatedName}",
                "POST /api/catalogs/{name}", "POST /api/catalogs/{name}/{id}/duplicate",
                "PUT /api/catalogs/{name}/{id}", "POST /api/catalogs/{name}/validate",
                "POST /api/catalogs/{name}/{id}/validate", "DELETE /api/catalogs/{name}/{id}",
                "POST /api/catalogs/{name}/batch-delete",
                "GET /api/documents/{name}/{id}", "GET /api/documents/{name}/{id}/related/{relatedName}",
                "POST /api/documents/{name}", "POST /api/documents/{name}/{id}/duplicate",
                "PUT /api/documents/{name}/{id}", "POST /api/documents/{name}/validate",
                "POST /api/documents/{name}/{id}/validate", "POST /api/documents/{name}/{id}/post",
                "GET /api/documents/{name}/{id}/posting-preview", "POST /api/documents/{name}/{id}/unpost",
                "DELETE /api/documents/{name}/{id}", "POST /api/documents/{name}/batch-delete",
                "GET /api/registers/{name}/movements", "GET /api/registers/{name}/balance",
                "GET /api/registers/{name}/turnover",
                // List feeds, groups, widget aggregates
                "GET /api/list/catalogs/{name}", "GET /api/list/documents/{name}",
                "GET /api/list/catalogs/{name}/groups", "GET /api/list/documents/{name}/groups",
                "GET /api/list/catalogs/{name}/aggregate", "GET /api/list/documents/{name}/aggregate",
                "GET /api/list/registers/{name}/movements", "GET /api/list/registers/{name}/balance",
                // Pickers, mentions, comments, tags, presence, actions, form validation
                "POST /api/ref-options/search", "GET /api/mentions", "GET /api/mentions/resolve",
                "GET /api/comments/{kind}/{name}/{id}", "POST /api/comments/{kind}/{name}/{id}",
                "POST /api/comments/{commentId}/reactions", "DELETE /api/comments/{commentId}",
                "GET /api/tags/{kind}/{name}", "GET /api/tags/{kind}/{name}/{id}",
                "POST /api/tags/{kind}/{name}/{id}/{tag}", "DELETE /api/tags/{kind}/{name}/{id}/{tag}",
                "GET /api/presence", "POST /api/presence",
                "GET /api/actions/{kind}/{name}", "POST /api/actions/{kind}/{name}/{key}",
                "GET /api/actions/{kind}/{name}/{key}/form", "POST /api/actions/{kind}/{name}/{key}/batch",
                "POST /api/form-validation/{kind}/{name}/{key}",
                "GET /api/notifications",
                // DivKit surfaces
                "GET /api/divkit/home", "GET /api/divkit/{*route}",
                "GET /api/divkit/catalogs/{name}", "GET /api/divkit/catalogs/{name}/{id}",
                "GET /api/divkit/catalogs/{name}/new", "GET /api/divkit/catalogs/{name}/{id}/duplicate",
                "GET /api/divkit/documents/{name}", "GET /api/divkit/documents/{name}/{id}",
                "GET /api/divkit/documents/{name}/new", "GET /api/divkit/documents/{name}/{id}/duplicate",
                "GET /api/divkit/registers/{name}",
                // Task inbox: assignment decides visibility; subject labels are masked by scope
                "GET /api/tasks")) {
            ROUTES.put(r, Route.SCOPED);
        }
        ROUTES.put("GET /api/events", Route.SCOPED_ELSEWHERE);
        for (String r : List.of(
                "GET /", "GET /api/theme", "GET /api/config", "GET /api/branding",
                "GET /api/divkit/shell", "GET /api/divkit/account", "GET /api/divkit/menu",
                "GET /api/divkit/login", "POST /api/divkit/page-action",
                "POST /api/notifications/{id}/read", "POST /api/notifications/read-all",
                "GET /api/process-definitions", "POST /api/processes/{definitionKey}",
                "GET /api/processes", "GET /api/processes/{instanceId}",
                "GET /api/processes/{instanceId}/history", "GET /api/processes/{instanceId}/executions",
                "POST /api/processes/{instanceId}/cancel", "POST /api/processes/{instanceId}/migrate",
                "POST /api/tasks/{workItemId}/claim", "GET /api/task-assignees",
                "GET /api/tasks/{workItemId}/history", "POST /api/tasks/{workItemId}/delegate",
                "POST /api/tasks/{workItemId}/complete",
                // Media is not record-bound yet (design §8, follow-up): no record data is served.
                "POST /api/media", "GET /api/media/{*key}",
                "GET /error", "* /error")) {
            ROUTES.put(r, Route.NO_ENTITY_DATA);
        }
        ROUTES.put("GET /api/settings", Route.ADMIN_ONLY);
        ROUTES.put("PUT /api/settings", Route.ADMIN_ONLY);
    }

    @Autowired WebApplicationContext context;
    @Autowired MetadataRegistry registry;
    @Autowired Jdbi jdbi;
    @Autowired CatalogCommandService catalogCommands;
    @Autowired DocumentCommandService documentCommands;
    @Autowired CatalogQueryService catalogQuery;
    @Autowired DocumentQueryService documentQuery;
    @Autowired CommentService comments;
    @Autowired PresenceRegistry presence;
    @Autowired NotificationService notifications;
    @Autowired RecordScopeCompiler compiler;
    @Autowired UiAccessService access;
    @Autowired AccessSubjectResolver subjects;

    private final ObjectMapper json = new ObjectMapper();
    private final Set<String> hits = new TreeSet<>();
    private final List<String> bodies = new ArrayList<>();
    private MockMvc mvc;

    private static boolean seeded;
    private static UUID ownerA, ownerB, tenantA, tenantB, tenantLocked, planBasic;
    private static UUID instanceA, instanceB, instanceUnderB, instanceMirror;
    private static UUID jobA, jobB;
    private static final Set<String> B_IDS = new LinkedHashSet<>();

    private static final Principal CUSTOMER_A = UsernamePasswordAuthenticationToken.authenticated(
            "a@leak.test", "n/a", List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
    private static final Principal STAFF = UsernamePasswordAuthenticationToken.authenticated(
            "staff@leak.test", "n/a", List.of(new SimpleGrantedAuthority("ROLE_STAFF")));

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .alwaysDo(result -> {
                    Object pattern = result.getRequest().getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
                    if (pattern != null) hits.add(result.getRequest().getMethod() + " " + pattern);
                })
                .build();
        if (!seeded) {
            seed();
            seeded = true;
        }
    }

    // ------------------------------------------------------------------------------- the seed

    private void seed() {
        AccessSubject system = AccessSubject.system();
        CatalogDescriptor owners = catalogQuery.require("LkOwners");
        CatalogDescriptor tenants = catalogQuery.require("LkTenants");
        CatalogDescriptor instances = catalogQuery.require("LkInstances");
        CatalogDescriptor plans = catalogQuery.require("LkPlans");
        DocumentDescriptor jobs = documentQuery.require("LkBuildJobs");

        ownerA = id(catalogCommands.create(owners, body("code", "OA", "description", "Owner Alpha",
                "email", "a@leak.test"), system));
        ownerB = id(catalogCommands.create(owners, body("code", "LEAK-B-O", "description", "LEAK-B-owner",
                "email", "b@leak.test"), system));
        planBasic = id(catalogCommands.create(plans, body("code", "BASIC", "description", "Basic plan"), system));
        tenantA = id(catalogCommands.create(tenants, body("code", "TA", "description", "Alpha tenant",
                "owner", ownerA.toString(), "note", "alpha"), system));
        tenantLocked = id(catalogCommands.create(tenants, body("code", "TL", "description", "Locked tenant",
                "owner", ownerA.toString(), "note", "locked"), system));
        tenantB = id(catalogCommands.create(tenants, body("code", "LEAK-B-T", "description", "LEAK-B-tenant",
                "owner", ownerB.toString(), "note", "LEAK-B-note"), system));
        instanceA = id(catalogCommands.create(instances, body("code", "IA", "description", "alpha-1",
                "tenant", tenantA.toString(), "plan", planBasic.toString()), system));
        instanceB = id(catalogCommands.create(instances, body("code", "LEAK-B-I", "description", "LEAK-B-instance",
                "tenant", tenantB.toString(), "plan", planBasic.toString()), system));
        // an A instance filed under B's instance: A must see it as a root with the parent withheld
        instanceUnderB = id(catalogCommands.create(instances, body("code", "IA2", "description", "alpha-orphan",
                "tenant", tenantA.toString(), "parent", instanceB.toString()), system));
        // an A instance whose non-scope ref points at B's tenant: rendered restricted, never searchable
        instanceMirror = id(catalogCommands.create(instances, body("code", "IA3", "description", "alpha-mirror",
                "tenant", tenantA.toString(), "mirror", tenantB.toString()), system));

        Map<String, Object> jobABody = body("tenant", tenantA.toString(), "note", "alpha job");
        jobABody.put("lines", List.of(body("instance", instanceA.toString(), "minutes", "5")));
        jobA = id(documentCommands.create(jobs, jobABody, system));
        Map<String, Object> jobBBody = body("tenant", tenantB.toString(), "note", "LEAK-B-job");
        jobBBody.put("lines", List.of(body("instance", instanceB.toString(), "minutes", "7")));
        jobB = id(documentCommands.create(jobs, jobBBody, system));
        documentCommands.post(jobs, jobA, system);
        documentCommands.post(jobs, jobB, system);

        InformationRegisterDescriptor facts = registry.allInformationRegisters().stream()
                .filter(d -> d.javaClass() == LkTenantFacts.class).findFirst().orElseThrow();
        InformationRegisterPersistence<LkTenantFacts> factStore = new InformationRegisterPersistence<>(jdbi, facts);
        factStore.write(fact(tenantA, "alpha fact"));
        factStore.write(fact(tenantB, "LEAK-B-fact"));

        comments.add("catalogs", "LkTenants", tenantB, ownerB.toString(), "LEAK-B-author", "LEAK-B-comment");
        comments.add("catalogs", "LkTenants", tenantA, ownerA.toString(), "Owner Alpha", "alpha comment");
        presence.onLocal("enter", "catalogs", "LkTenants", tenantB.toString(), ownerB.toString(),
                "LEAK-B-viewer", null);
        notifications.notify(NotificationRequest.to(ownerA.toString()).type("assignment")
                .title("LEAK-B-notification").link("catalogs/LkTenants/" + tenantB).build());
        notifications.notify(NotificationRequest.to(ownerA.toString()).type("assignment")
                .title("alpha notification").link("catalogs/LkTenants/" + tenantA).build());

        B_IDS.addAll(List.of(ownerB.toString(), tenantB.toString(), instanceB.toString(), jobB.toString()));
    }

    private static LkTenantFacts fact(UUID tenant, String text) {
        LkTenantFacts f = new LkTenantFacts();
        f.setTenant(Ref.of(LkTenant.class, tenant));
        f.setFact(text);
        return f;
    }

    // ------------------------------------------------------------------------------- the suite

    @Test
    void noEndpointLeaksAnotherOwnersRecords() throws Exception {
        LeakTestApp.TOUCHES.set(0);
        readsAreScoped();
        restrictedRefsAndSearchAreNoOracle();
        treesPromoteOrphans();
        registersAreScopedByDimension();
        recordSidecarsAreScoped();
        divkitSurfacesAreScoped();
        writesAreScoped();
        actionsAreScoped();
        cursorsAreBoundToTheirSubject();

        for (String body : bodies) {
            assertThat(body).doesNotContain("LEAK-B");
            for (String id : B_IDS) {
                assertThat(body).as("response leaks B's id " + id).doesNotContain(id);
            }
        }

        Set<String> unexercised = new TreeSet<>();
        ROUTES.forEach((route, kind) -> {
            if (kind == Route.SCOPED && !hits.contains(route)) unexercised.add(route);
        });
        assertThat(unexercised).as("SCOPED routes the leak test never exercised").isEmpty();
    }

    private void readsAreScoped() throws Exception {
        Map<String, Object> page = jsonMap(ok(get("/api/list/catalogs/LkTenants").param("count", "exact")));
        assertThat(ids(page.get("rows"))).contains(tenantA.toString(), tenantLocked.toString());
        assertThat(page.get("total")).isEqualTo(ids(page.get("rows")).size());
        ok(get("/api/list/catalogs/LkTenants").param("count", "estimate"));
        ok(get("/api/list/catalogs/LkTenants").param("q", "tenant"));
        ok(get("/api/list/catalogs/LkTenants").param("ids", tenantA.toString(), tenantB.toString()));
        assertThat(ids(jsonMap(ok(get("/api/list/catalogs/LkTenants")
                .param("ids", tenantB.toString()))).get("rows"))).isEmpty();

        Map<String, Object> groups = jsonMap(ok(get("/api/list/catalogs/LkTenants/groups").param("groupBy", "owner")));
        assertThat((List<?>) groups.get("groups")).hasSize(1);
        Map<String, Object> agg = jsonMap(ok(get("/api/list/catalogs/LkTenants/aggregate")
                .param("metric", "count").param("groupBy", "owner")));
        assertThat((List<?>) agg.get("buckets")).hasSize(1);

        ok(get("/api/catalogs/LkTenants/" + tenantA));
        status(get("/api/catalogs/LkTenants/" + tenantB), 404);
        status(get("/api/catalogs/LkOwners/" + ownerB), 404);
        ok(get("/api/catalogs/LkTenants/" + tenantA + "/related/instances"));
        ok(get("/api/catalogs/LkTenants/" + tenantA + "/related/facts"));
        status(get("/api/catalogs/LkTenants/" + tenantB + "/related/instances"), 404);

        ok(get("/api/list/documents/LkBuildJobs"));
        ok(get("/api/list/documents/LkBuildJobs").param("q", "LEAK"));
        ok(get("/api/list/documents/LkBuildJobs/groups").param("groupBy", "tenant"));
        ok(get("/api/list/documents/LkBuildJobs/aggregate").param("metric", "count"));
        ok(get("/api/documents/LkBuildJobs/" + jobA));
        status(get("/api/documents/LkBuildJobs/" + jobB), 404);
        status(get("/api/documents/LkBuildJobs/" + jobA + "/related/none"), 404);
        status(get("/api/documents/LkBuildJobs/" + jobB + "/posting-preview"), 404);
        ok(get("/api/documents/LkBuildJobs/" + jobA + "/posting-preview"));

        ok(post("/api/ref-options/search").contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetKind\":\"catalog\",\"targetName\":\"LkTenants\",\"query\":\"\"}"));
        ok(post("/api/ref-options/search").contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetKind\":\"document\",\"targetName\":\"LkBuildJobs\",\"query\":\"\"}"));
    }

    private void restrictedRefsAndSearchAreNoOracle() throws Exception {
        Map<String, Object> page = jsonMap(ok(get("/api/list/catalogs/LkInstances")));
        Map<String, Object> mirror = row(page.get("rows"), instanceMirror);
        assertThat(mirror).containsEntry("mirrorRestricted", true).containsEntry("mirror", null);
        assertThat(mirror.get("mirrorDisplay")).isEqualTo(RefResolver.RESTRICTED_DISPLAY);

        // searching by the display of B's tenant must not find A's record that points at it
        assertThat(ids(jsonMap(ok(get("/api/list/catalogs/LkInstances").param("q", "LEAK-B-tenant")))
                .get("rows"))).isEmpty();
        assertThat(ids(jsonMap(ok(get("/api/list/catalogs/LkInstances").param("q", "Basic"))).get("rows")))
                .contains(instanceA.toString());
    }

    private void treesPromoteOrphans() throws Exception {
        List<Map<String, Object>> tree = jsonList(ok(get("/api/catalogs/LkInstances/tree")));
        assertThat(ids(tree)).contains(instanceUnderB.toString());
        assertThat(row(tree, instanceUnderB)).containsEntry("parent", null).containsEntry("parentRestricted", true);
        assertThat(ids(jsonList(ok(get("/api/catalogs/LkInstances/children"))))).contains(instanceUnderB.toString());
        assertThat(jsonList(ok(get("/api/catalogs/LkInstances/children").param("parent", instanceB.toString()))))
                .allSatisfy(r -> assertThat(r.get("parent")).isNull());
    }

    private void registersAreScopedByDimension() throws Exception {
        List<Map<String, Object>> movements = jsonList(ok(get("/api/registers/LkUsage/movements")));
        assertThat(movements).hasSize(1);
        List<Map<String, Object>> balance = jsonList(ok(get("/api/registers/LkUsage/balance")));
        assertThat(balance).hasSize(1);
        ok(get("/api/registers/LkUsage/turnover").param("from", "2000-01-01T00:00:00").param("to", "2999-01-01T00:00:00"));
        Map<String, Object> window = jsonMap(ok(get("/api/list/registers/LkUsage/movements")));
        assertThat(window.get("total")).isEqualTo(1);
        assertThat(jsonMap(ok(get("/api/list/registers/LkUsage/balance"))).get("total")).isEqualTo(1);
    }

    private void recordSidecarsAreScoped() throws Exception {
        assertThat(jsonList(ok(get("/api/mentions").param("q", "LEAK-B")))).isEmpty();
        ok(get("/api/mentions").param("q", "Alpha"));
        Map<String, Object> resolved = jsonMap(ok(get("/api/mentions/resolve")
                .param("kind", "catalogs").param("name", "LkTenants").param("id", tenantB.toString())));
        // the resolve response echoes the requested id; it must be unreadable with no display
        bodies.remove(bodies.size() - 1);
        assertThat(resolved).containsEntry("readable", false).containsEntry("display", null);

        ok(get("/api/comments/catalogs/LkTenants/" + tenantA));
        status(get("/api/comments/catalogs/LkTenants/" + tenantB), 404);
        status(post("/api/comments/catalogs/LkTenants/" + tenantB).contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"hi\"}"), 404);
        Map<String, Object> mine = jsonMap(ok(post("/api/comments/catalogs/LkTenants/" + tenantA)
                .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"mine\"}")));
        ok(post("/api/comments/" + mine.get("id") + "/reactions").contentType(MediaType.APPLICATION_JSON)
                .content("{\"emoji\":\"👍\"}"));
        UUID bComment = comments.list("catalogs", "LkTenants", tenantB).get(0).id();
        status(post("/api/comments/" + bComment + "/reactions").contentType(MediaType.APPLICATION_JSON)
                .content("{\"emoji\":\"👍\"}"), 404);
        status(delete("/api/comments/" + bComment), 404);

        ok(get("/api/tags/catalogs/LkTenants"));
        ok(get("/api/tags/catalogs/LkTenants/" + tenantA));
        status(get("/api/tags/catalogs/LkTenants/" + tenantB), 404);
        status(post("/api/tags/catalogs/LkTenants/" + tenantB + "/" + UUID.randomUUID()), 404);
        status(delete("/api/tags/catalogs/LkTenants/" + tenantB + "/" + UUID.randomUUID()), 404);

        Map<String, Object> snapshot = jsonMap(ok(get("/api/presence")));
        assertThat((List<?>) snapshot.get("records")).isEmpty();
        status(post("/api/presence").contentType(MediaType.APPLICATION_JSON)
                .content("{\"path\":\"/catalogs/LkTenants/" + tenantB + "\",\"action\":\"enter\"}"), 404);

        Map<String, Object> feed = jsonMap(ok(get("/api/notifications")));
        assertThat((List<?>) feed.get("items")).hasSize(1);

        ok(get("/api/tasks"));
    }

    private void divkitSurfacesAreScoped() throws Exception {
        ok(get("/api/divkit/home"));
        ok(get("/api/divkit/leak-dash"));
        ok(get("/api/divkit/catalogs/LkTenants"));
        ok(get("/api/divkit/catalogs/LkTenants/" + tenantA));
        status(get("/api/divkit/catalogs/LkTenants/" + tenantB), 404);
        String draft = ok(get("/api/divkit/catalogs/LkTenants/new"));
        assertThat(draft).as("the policy default (the signed-in owner) pre-fills the New form")
                .contains(ownerA.toString());
        ok(get("/api/divkit/catalogs/LkTenants/" + tenantA + "/duplicate"));
        status(get("/api/divkit/catalogs/LkTenants/" + tenantB + "/duplicate"), 404);
        ok(get("/api/divkit/documents/LkBuildJobs"));
        ok(get("/api/divkit/documents/LkBuildJobs/" + jobA));
        status(get("/api/divkit/documents/LkBuildJobs/" + jobB), 404);
        ok(get("/api/divkit/documents/LkBuildJobs/new"));
        ok(get("/api/divkit/documents/LkBuildJobs/" + jobA + "/duplicate"));
        status(get("/api/divkit/documents/LkBuildJobs/" + jobB + "/duplicate"), 404);
        ok(get("/api/divkit/registers/LkUsage"));
    }

    private void writesAreScoped() throws Exception {
        status(put("/api/catalogs/LkTenants/" + tenantB).contentType(MediaType.APPLICATION_JSON)
                .content("{\"description\":\"pwned\"}"), 404);
        status(delete("/api/catalogs/LkTenants/" + tenantB), 404);
        status(post("/api/catalogs/LkTenants/" + tenantB + "/duplicate"), 404);
        status(post("/api/catalogs/LkTenants/" + tenantB + "/validate").contentType(MediaType.APPLICATION_JSON)
                .content("{}"), 404);
        ok(post("/api/catalogs/LkTenants/validate").contentType(MediaType.APPLICATION_JSON).content("{}"));
        Map<String, Object> batch = jsonMap(ok(post("/api/catalogs/LkTenants/batch-delete")
                .contentType(MediaType.APPLICATION_JSON).content("{\"ids\":[\"" + tenantB + "\"]}")));
        bodies.remove(bodies.size() - 1); // the batch summary echoes failed ids
        assertThat(batch).containsEntry("ok", 0);

        // create: the policy default stamps the owner, even over an explicit foreign owner
        Map<String, Object> created = jsonMap(ok(post("/api/catalogs/LkTenants").contentType(MediaType.APPLICATION_JSON)
                .content("{\"description\":\"New tenant\",\"owner\":\"" + ownerB + "\"}")));
        bodies.remove(bodies.size() - 1);
        assertThat(created.get("owner")).isEqualTo(ownerA.toString());
        // cannot move a record out of the write scope; cannot reference an unreadable record
        status(put("/api/catalogs/LkTenants/" + tenantA).contentType(MediaType.APPLICATION_JSON)
                .content("{\"owner\":\"" + ownerB + "\"}"), 422);
        ok(put("/api/catalogs/LkTenants/" + tenantA).contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"alpha\"}"));
        ok(post("/api/catalogs/LkTenants/" + tenantA + "/duplicate"));
        ok(post("/api/catalogs/LkTenants/" + tenantA + "/validate").contentType(MediaType.APPLICATION_JSON)
                .content("{}"));
        status(post("/api/catalogs/LkInstances").contentType(MediaType.APPLICATION_JSON)
                .content("{\"description\":\"x\",\"tenant\":\"" + tenantB + "\"}"), 422);
        status(post("/api/catalogs/LkInstances").contentType(MediaType.APPLICATION_JSON)
                .content("{\"description\":\"x\",\"tenant\":\"" + tenantA + "\",\"parent\":\"" + instanceB + "\"}"), 422);
        Map<String, Object> scratch = jsonMap(ok(post("/api/catalogs/LkInstances").contentType(MediaType.APPLICATION_JSON)
                .content("{\"description\":\"scratch\",\"tenant\":\"" + tenantA + "\"}")));
        status(delete("/api/catalogs/LkInstances/" + scratch.get("id")), 200);

        status(put("/api/documents/LkBuildJobs/" + jobB).contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"pwned\"}"), 404);
        status(post("/api/documents/LkBuildJobs/" + jobB + "/post"), 404);
        status(post("/api/documents/LkBuildJobs/" + jobB + "/unpost"), 404);
        status(post("/api/documents/LkBuildJobs/" + jobB + "/duplicate"), 404);
        status(post("/api/documents/LkBuildJobs/" + jobB + "/validate").contentType(MediaType.APPLICATION_JSON)
                .content("{}"), 404);
        status(delete("/api/documents/LkBuildJobs/" + jobB), 404);
        Map<String, Object> docBatch = jsonMap(ok(post("/api/documents/LkBuildJobs/batch-delete")
                .contentType(MediaType.APPLICATION_JSON).content("{\"ids\":[\"" + jobB + "\"]}")));
        bodies.remove(bodies.size() - 1);
        assertThat(docBatch).containsEntry("ok", 0);
        // a line referencing B's instance is rejected
        status(post("/api/documents/LkBuildJobs").contentType(MediaType.APPLICATION_JSON)
                .content("{\"tenant\":\"" + tenantA + "\",\"lines\":[{\"instance\":\"" + instanceB + "\",\"minutes\":1}]}"), 422);
        Map<String, Object> job = jsonMap(ok(post("/api/documents/LkBuildJobs").contentType(MediaType.APPLICATION_JSON)
                .content("{\"tenant\":\"" + tenantA + "\",\"lines\":[{\"instance\":\"" + instanceA + "\",\"minutes\":1}]}")));
        Object jobId = job.get("id");
        ok(post("/api/documents/LkBuildJobs/validate").contentType(MediaType.APPLICATION_JSON).content("{}"));
        ok(post("/api/documents/LkBuildJobs/" + jobId + "/validate").contentType(MediaType.APPLICATION_JSON).content("{}"));
        ok(put("/api/documents/LkBuildJobs/" + jobId).contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"x\"}"));
        ok(post("/api/documents/LkBuildJobs/" + jobId + "/post"));
        ok(post("/api/documents/LkBuildJobs/" + jobId + "/unpost"));
        ok(post("/api/documents/LkBuildJobs/" + jobId + "/duplicate"));
        status(delete("/api/documents/LkBuildJobs/" + jobId), 200);

        status(post("/api/form-validation/catalogs/LkTenants/none").contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\"" + tenantB + "\",\"values\":{}}"), 404);
    }

    private void actionsAreScoped() throws Exception {
        ok(get("/api/actions/catalogs/LkTenants").param("id", tenantA.toString()));
        status(get("/api/actions/catalogs/LkTenants").param("id", tenantB.toString()), 404);
        status(post("/api/actions/catalogs/LkTenants/touch").param("id", tenantB.toString()), 404);
        status(post("/api/actions/catalogs/LkTenants/touch").param("id", tenantLocked.toString()), 409);
        ok(post("/api/actions/catalogs/LkTenants/touch").param("id", tenantA.toString()));
        status(get("/api/actions/catalogs/LkTenants/touch/form").param("id", tenantB.toString()), 404);
        Map<String, Object> batch = jsonMap(ok(post("/api/actions/catalogs/LkTenants/touch/batch")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ids\":[\"" + tenantA + "\",\"" + tenantB + "\",\"" + tenantLocked + "\"]}")));
        bodies.remove(bodies.size() - 1);
        assertThat(batch).containsEntry("ok", 1);
        assertThat(LeakTestApp.TOUCHES.get()).isEqualTo(2);
    }

    private void cursorsAreBoundToTheirSubject() throws Exception {
        Map<String, Object> first = jsonMap(ok(get("/api/list/catalogs/LkTenants").param("limit", "1")));
        String cursor = (String) first.get("nextCursor");
        assertThat(cursor).contains("~");
        ok(get("/api/list/catalogs/LkTenants").param("limit", "1").param("cursor", cursor));
        int replayed = mvc.perform(get("/api/list/catalogs/LkTenants").principal(STAFF)
                .param("limit", "1").param("cursor", cursor)).andReturn().getResponse().getStatus();
        assertThat(replayed).isEqualTo(400);
    }

    // --------------------------------------------------------------------------- SSE delivery

    @Test
    void liveEventsOnlyReachSubscribersWhoCanReadTheRecord() {
        UiEventPublisher publisher = new UiEventPublisher(access, false, CapturingEmitter::new);
        publisher.setRecordScopes(compiler, jdbi, registry);
        AccessSubject.User a = subjects.build("a@leak.test", Set.of("CUSTOMER"));
        CapturingEmitter toA = (CapturingEmitter) publisher.subscribe(a, ownerA.toString(), "a@leak.test");
        CapturingEmitter toStaff = (CapturingEmitter) publisher.subscribe(
                AccessSubject.user("staff@leak.test", Set.of("STAFF")), "staff", "staff@leak.test");

        publisher.publish("updated", "catalog", "LkTenants", tenantB, "LEAK-B-T");
        publisher.publish("updated", "catalog", "LkTenants", tenantA, "TA");
        publisher.publish("created", "comment", "LkTenants", tenantB, null);
        publisher.publishPresence("catalogs", "LkTenants", tenantB.toString(), List.of());
        publisher.publish("updated", "document", "LkBuildJobs", jobB, "LEAK-B");
        publisher.publishNotification(ownerA.toString(), Map.of("link", "catalogs/LkTenants/" + tenantB,
                "title", "LEAK-B"));
        publisher.publish("changed", "register", "*", jobB, null);

        String seenByA = String.join("\n", toA.sent);
        assertThat(seenByA).contains(tenantA.toString()).doesNotContain(tenantB.toString()).doesNotContain("LEAK-B");
        assertThat(toA.sent).anySatisfy(s -> assertThat(s).contains("entityName:*").contains("register"));
        assertThat(String.join("\n", toStaff.sent)).contains(tenantB.toString());
    }

    /** An emitter that records what would be written to the stream. */
    static final class CapturingEmitter extends SseEmitter {
        final List<String> sent = new CopyOnWriteArrayList<>();

        CapturingEmitter() {
            super(0L);
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            StringBuilder sb = new StringBuilder();
            builder.build().forEach(part -> sb.append(String.valueOf(part.getData())));
            sent.add(sb.toString().replace('=', ':'));
        }
    }

    // --------------------------------------------------------------------------- coverage guard

    @Test
    void everyRouteIsClassified() {
        RequestMappingHandlerMapping mapping = context.getBean("requestMappingHandlerMapping",
                RequestMappingHandlerMapping.class);
        Set<String> unclassified = new TreeSet<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mapping.getHandlerMethods().entrySet()) {
            Set<String> methods = new TreeSet<>();
            e.getKey().getMethodsCondition().getMethods().forEach(m -> methods.add(m.name()));
            if (methods.isEmpty()) methods.add("*");
            for (String pattern : e.getKey().getPatternValues()) {
                for (String method : methods) {
                    String route = method + " " + pattern;
                    if (!ROUTES.containsKey(route)) unclassified.add(route + "  (" + e.getValue() + ")");
                }
            }
        }
        assertThat(unclassified)
                .as("routes without a record-policy classification — add each to ROUTES as SCOPED "
                        + "(and exercise it in the leak test), NO_ENTITY_DATA or ADMIN_ONLY")
                .isEmpty();
    }

    // --------------------------------------------------------------------------- helpers

    private String ok(MockHttpServletRequestBuilder request) throws Exception {
        return ok(request, 200);
    }

    private String ok(MockHttpServletRequestBuilder request, int expected) throws Exception {
        return status(request, expected);
    }

    private String status(MockHttpServletRequestBuilder request, int expected) throws Exception {
        MvcResult result = mvc.perform(request.principal(CUSTOMER_A)).andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(result.getResponse().getStatus())
                .as(result.getRequest().getMethod() + " " + result.getRequest().getRequestURI()
                        + "?" + result.getRequest().getQueryString() + " → " + body
                        + describe(result.getResolvedException()))
                .isEqualTo(expected);
        bodies.add(body);
        return body;
    }

    private static String describe(Throwable error) {
        if (error == null) return "";
        java.io.StringWriter out = new java.io.StringWriter();
        error.printStackTrace(new java.io.PrintWriter(out));
        String trace = out.toString();
        return "\n" + trace.substring(0, Math.min(trace.length(), 4000));
    }

    private Map<String, Object> jsonMap(String body) throws Exception {
        return json.readValue(body, new TypeReference<>() {});
    }

    private List<Map<String, Object>> jsonList(String body) throws Exception {
        return json.readValue(body, new TypeReference<>() {});
    }

    @SuppressWarnings("unchecked")
    private static List<String> ids(Object rows) {
        List<String> out = new ArrayList<>();
        for (Object r : (List<Object>) rows) {
            Object id = ((Map<String, Object>) r).get("id");
            out.add(String.valueOf(id));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> row(Object rows, UUID id) {
        for (Object r : (List<Object>) rows) {
            if (id.toString().equals(String.valueOf(((Map<String, Object>) r).get("id")))) {
                return (Map<String, Object>) r;
            }
        }
        throw new AssertionError("row " + id + " not found in " + rows);
    }

    private static UUID id(Map<String, Object> row) {
        Object id = row.get("_id");
        return id instanceof UUID u ? u : UUID.fromString(String.valueOf(id));
    }

    private static Map<String, Object> body(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
