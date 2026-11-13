package su.onno.ui;

import su.onno.access.AccessSubject;
import su.onno.ui.openinlist.OpenInListTestApp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * The "Open in list" header action of a record surface (#410): offered to every reader (it needs no
 * write access), placed by {@code f.action("openInList")}, omitted when an authored page replaces
 * the list route, and pointing at the list feed's focus window. Also pins that the built-in
 * duplicate/delete actions honor their placement overrides on catalogs and documents alike.
 */
@SpringBootTest(classes = OpenInListTestApp.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:open_in_list;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "onno.scan-packages=su.onno.ui.openinlist.model",
        "onno.ui.locale=en",
        "spring.main.banner-mode=off"
})
class OpenInListActionTest {

    private static final Principal READER = principal("reader@ol.test", "READER");
    private static final Principal WRITER = principal("writer@ol.test", "WRITER");
    private static final Principal OUTSIDER = principal("outsider@ol.test", "OUTSIDER");

    @Autowired WebApplicationContext context;
    @Autowired CatalogCommandService catalogCommands;
    @Autowired DocumentCommandService documentCommands;
    @Autowired CatalogQueryService catalogQuery;
    @Autowired DocumentQueryService documentQuery;

    private final ObjectMapper json = new ObjectMapper();
    private MockMvc mvc;

    private static boolean seeded;
    private static UUID note, pinned, paged, task, event;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        if (!seeded) {
            AccessSubject system = AccessSubject.system();
            note = createCatalog("OlNotes", system);
            pinned = createCatalog("OlPinned", system);
            paged = createCatalog("OlPaged", system);
            task = id(documentCommands.create(documentQuery.require("OlTasks"), new LinkedHashMap<>(), system));
            event = id(documentCommands.create(documentQuery.require("OlEvents"), new LinkedHashMap<>(), system));
            seeded = true;
        }
    }

    @Test
    void aReaderWithoutWriteAccessGetsOpenInListInTheMenu() throws Exception {
        List<Map<String, Object>> actions = actions("/api/divkit/catalogs/OlNotes/" + note, READER);

        assertThat(urls(actions)).containsExactly("onno://catalogs/OlNotes?focus=" + note);
        assertThat(action(actions, "?focus="))
                .containsEntry("icon", "list")
                .containsEntry("label", "Open in list")
                .containsEntry("placement", "menu")
                .containsEntry("tone", "normal");
    }

    @Test
    void aWriterGetsOpenInListAlongsideDuplicateAndDelete() throws Exception {
        List<Map<String, Object>> actions = actions("/api/divkit/catalogs/OlNotes/" + note, WRITER);

        assertThat(urls(actions)).containsExactly(
                "onno://catalogs/OlNotes?focus=" + note,
                "onno://catalogs/OlNotes/" + note + "/duplicate",
                "onno://delete/catalogs/OlNotes/" + note);
        assertThat(actions).allSatisfy(a -> assertThat(a).containsEntry("placement", "menu"));
    }

    @Test
    void catalogPlacementOverridesApplyToOpenInListAndTheBuiltIns() throws Exception {
        List<Map<String, Object>> actions = actions("/api/divkit/catalogs/OlPinned/" + pinned, WRITER);

        assertThat(action(actions, "?focus="))
                .containsEntry("placement", "primary").containsEntry("tone", "accent");
        assertThat(action(actions, "/duplicate")).containsEntry("placement", "primary");
        assertThat(urls(actions)).noneMatch(url -> url.startsWith("onno://delete/"));
    }

    @Test
    void documentPlacementOverridesHideOpenInListAndPromoteDuplicate() throws Exception {
        List<Map<String, Object>> actions = actions("/api/divkit/documents/OlTasks/" + task, WRITER);

        assertThat(urls(actions)).noneMatch(url -> url.contains("?focus="));
        assertThat(action(actions, "/duplicate")).containsEntry("placement", "primary");
        assertThat(action(actions, "onno://delete/")).containsEntry("placement", "menu");
    }

    @Test
    void aDocumentReaderGetsOpenInListAndTheFocusWindowContainsTheRecord() throws Exception {
        List<Map<String, Object>> actions = actions("/api/divkit/documents/OlEvents/" + event, READER);

        assertThat(urls(actions)).containsExactly("onno://documents/OlEvents?focus=" + event);

        // The action's target: the list feed anchors its window on the record.
        Map<String, Object> window = json(mvc.perform(get("/api/list/documents/OlEvents")
                .param("focus", event.toString()).principal(READER)).andReturn());
        assertThat(window).containsEntry("focus", event.toString());
    }

    @Test
    void anAuthoredPageOnTheListRouteSuppressesOpenInList() throws Exception {
        List<Map<String, Object>> actions = actions("/api/divkit/catalogs/OlPaged/" + paged, WRITER);

        assertThat(urls(actions)).noneMatch(url -> url.contains("?focus="))
                .contains("onno://catalogs/OlPaged/" + paged + "/duplicate");
    }

    @Test
    void theListRouteHandsTheFocusToTheListIsland() throws Exception {
        String focused = mvc.perform(get("/api/divkit/catalogs/OlNotes").param("focus", note.toString())
                .principal(READER)).andReturn().getResponse().getContentAsString();
        assertThat(focused).contains("\"focus\":\"" + note + "\"");

        for (String ignored : List.of("not-a-uuid", "")) {
            String plain = mvc.perform(get("/api/divkit/documents/OlEvents").param("focus", ignored)
                    .principal(READER)).andReturn().getResponse().getContentAsString();
            assertThat(plain).contains("onno-list").doesNotContain("\"focus\"");
        }
    }

    @Test
    void aUserWhoCannotReadTheEntityGetsNoRecordSurfaceAtAll() throws Exception {
        int status = mvc.perform(get("/api/divkit/catalogs/OlNotes/" + note).principal(OUTSIDER))
                .andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(403);
    }

    @Test
    void documentRefreshUsesTheSameReadOnlyActionsAsTheRecordSurface() throws Exception {
        String recordPath = "/documents/OlEvents/" + event;
        Map<String, Object> refresh = json(mvc.perform(get("/api/divkit/record-refresh" + recordPath)
                .principal(READER)).andReturn());

        assertThat(refresh).containsOnlyKeys("record", "actions", "readOnly")
                .containsEntry("readOnly", true)
                .containsEntry("actions", actions("/api/divkit" + recordPath, READER));
        assertThat(((Map<?, ?>) refresh.get("record")).get("id")).isEqualTo(event.toString());
    }

    @Test
    void catalogRefreshPreservesActionPlacementAndEntityReadAccess() throws Exception {
        String recordPath = "/catalogs/OlPinned/" + pinned;
        String refreshPath = "/api/divkit/record-refresh" + recordPath;
        Map<String, Object> refresh = json(mvc.perform(get(refreshPath).principal(WRITER)).andReturn());

        assertThat(refresh).containsEntry("readOnly", false)
                .containsEntry("actions", actions("/api/divkit" + recordPath, WRITER));
        assertThat(mvc.perform(get(refreshPath).principal(OUTSIDER)).andReturn().getResponse().getStatus())
                .isEqualTo(403);
    }

    // --- helpers ---------------------------------------------------------------

    /** The header actions of a record surface: every map in the response that carries an onno:// url. */
    private List<Map<String, Object>> actions(String path, Principal who) throws Exception {
        MvcResult result = mvc.perform(get(path).principal(who)).andReturn();
        assertThat(result.getResponse().getStatus()).as(path).isEqualTo(200);
        List<Map<String, Object>> out = new ArrayList<>();
        Object descriptor = json.readValue(result.getResponse().getContentAsString(), Object.class);
        collectActionLists(descriptor, out);
        return out;
    }

    /** Walk the DivKit tree for the entity form's {@code actions} list (maps with a placement). */
    @SuppressWarnings("unchecked")
    private static void collectActionLists(Object node, List<Map<String, Object>> out) {
        if (node instanceof Map<?, ?> map) {
            Object actions = map.get("actions");
            if (actions instanceof List<?> list && !list.isEmpty()
                    && list.stream().allMatch(a -> a instanceof Map<?, ?> m && m.containsKey("placement"))) {
                if (out.isEmpty()) {
                    list.forEach(a -> out.add((Map<String, Object>) a));
                }
                return;
            }
            map.values().forEach(v -> collectActionLists(v, out));
        } else if (node instanceof List<?> list) {
            list.forEach(v -> collectActionLists(v, out));
        }
    }

    private static List<String> urls(List<Map<String, Object>> actions) {
        return actions.stream().map(a -> String.valueOf(a.get("url"))).toList();
    }

    private static Map<String, Object> action(List<Map<String, Object>> actions, String urlPart) {
        return actions.stream().filter(a -> String.valueOf(a.get("url")).contains(urlPart)).findFirst()
                .orElseThrow(() -> new AssertionError("no action with url containing " + urlPart + " in " + actions));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> json(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return json.readValue(result.getResponse().getContentAsString(), Map.class);
    }

    private UUID createCatalog(String name, AccessSubject system) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("description", name + " record");
        return id(catalogCommands.create(catalogQuery.require(name), body, system));
    }

    private static UUID id(Map<String, Object> row) {
        Object id = row.get("_id");
        return id instanceof UUID u ? u : UUID.fromString(String.valueOf(id));
    }

    private static Principal principal(String name, String role) {
        return UsernamePasswordAuthenticationToken.authenticated(name, "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }
}
