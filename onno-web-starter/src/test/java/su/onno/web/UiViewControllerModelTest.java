package su.onno.web;

import su.onno.web.uimodel.UiModelTestApp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.security.Principal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * {@code GET /api/ui/model}: the business model as the caller sees it. Only entities the caller can
 * read are listed, each with its route, display title, bounded context and the caller's write right.
 */
@SpringBootTest(classes = UiModelTestApp.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:ui_model;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "onno.scan-packages=su.onno.web.uimodel.model",
        "onno.ui.locale=en",
        "spring.main.banner-mode=off"
})
class UiViewControllerModelTest {

    private static final Principal READER = principal("reader@um.test", "READER");
    private static final Principal WRITER = principal("writer@um.test", "WRITER");
    private static final Principal HR = principal("hr@um.test", "HR");
    private static final Principal OUTSIDER = principal("outsider@um.test", "OUTSIDER");
    private static final Principal ADMIN = principal("admin@um.test", "ADMIN");

    @Autowired WebApplicationContext context;

    private final ObjectMapper json = new ObjectMapper();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void listsOnlyReadableEntitiesWithRouteTitleContextAndReadOnlyRights() throws Exception {
        Map<String, Map<String, Object>> entities = entities(READER);

        assertThat(entities).containsOnlyKeys("UmCustomers", "UmOrders");
        assertThat(entities.get("UmCustomers"))
                .containsEntry("kind", "catalogs")
                .containsEntry("route", "/catalogs/" + entities.get("UmCustomers").get("name"))
                .containsEntry("title", "Customers")
                .containsEntry("context", "Sales")
                .containsEntry("canWrite", false)
                // The client picks entity icons; the model carries none.
                .doesNotContainKey("icon");
        assertThat(entities.get("UmOrders"))
                .containsEntry("kind", "documents")
                .containsEntry("route", "/documents/" + entities.get("UmOrders").get("name"))
                // No explicit title or context: the logical name and a blank context.
                .containsEntry("title", "UmOrders")
                .containsEntry("context", "")
                .containsEntry("canWrite", false);
    }

    @Test
    void aWriterSeesTheSameEntitiesAsWritable() throws Exception {
        Map<String, Map<String, Object>> entities = entities(WRITER);

        assertThat(entities).containsOnlyKeys("UmCustomers", "UmOrders");
        assertThat(entities.values()).allSatisfy(e -> assertThat(e).containsEntry("canWrite", true));
    }

    @Test
    void eachRoleSeesItsOwnSliceOfTheModel() throws Exception {
        assertThat(entities(HR)).containsOnlyKeys("UmSalaries");
        assertThat(entities(HR).get("UmSalaries"))
                .containsEntry("context", "HR").containsEntry("canWrite", true);
        assertThat(entities(OUTSIDER)).isEmpty();
    }

    @Test
    void carriesTheSignedInUser() throws Exception {
        Map<String, Object> model = model(READER);
        assertThat((Map<String, Object>) model.get("user")).containsEntry("username", "reader@um.test");
        assertThat(model).containsKeys("features", "entities");
        // Branding is served once, by /api/branding; read-only mode by /api/config.
        assertThat(model).doesNotContainKey("app");
    }

    @Test
    @SuppressWarnings("unchecked")
    void listsReadableInformationRegistersAndConstantsForAdministrators() throws Exception {
        assertThat((List<Map<String, Object>>) model(READER).get("informationRegisters")).singleElement()
                .satisfies(r -> assertThat(r).containsEntry("logicalName", "UmRates").containsEntry("title", "Rates")
                        .containsEntry("context", "Sales").containsEntry("periodic", true));
        assertThat((List<?>) model(HR).get("informationRegisters")).isEmpty();
        assertThat((List<?>) model(READER).get("constants")).isEmpty();
        assertThat((List<Map<String, Object>>) model(ADMIN).get("constants"))
                .containsExactly(Map.of("name", "UmShopName", "type", "String"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void informationRegisterSurfaceIsReadOnlyAndRequiresReadAccess() throws Exception {
        var response = mvc.perform(get("/api/ui/view/information-registers/um_rates").principal(READER))
                .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        Map<String, Object> view = json.readValue(response.getContentAsString(), Map.class);
        var register = (Map<String, Object>) view.get("informationRegister");
        assertThat(register).containsEntry("periodic", true);
        var list = (Map<String, Object>) register.get("list");
        assertThat(list).containsEntry("canWrite", false).containsEntry("newUrl", null)
                .containsEntry("feed", "/api/list/information-registers/um_rates");
        assertThat((List<Map<String, Object>>) list.get("columns"))
                .extracting(c -> c.get("fieldName")).containsExactly("period", "currency", "rate");
        assertThat(((List<Map<String, Object>>) list.get("columns")).getFirst()).containsEntry("label", "Date");
        assertThat(((List<Map<String, Object>>) list.get("filters")).getFirst()).containsEntry("label", "Date");
        assertThat(mvc.perform(get("/api/ui/view/information-registers/um_rates").principal(HR))
                .andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(mvc.perform(get("/api/ui/view/information-registers/missing").principal(READER))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> model(Principal who) throws Exception {
        var response = mvc.perform(get("/api/ui/model").principal(who)).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        return json.readValue(response.getContentAsString(), Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> entities(Principal who) throws Exception {
        Map<String, Map<String, Object>> out = new java.util.LinkedHashMap<>();
        for (Map<String, Object> e : (List<Map<String, Object>>) model(who).get("entities")) {
            out.put(String.valueOf(e.get("logicalName")), e);
        }
        return out;
    }

    private static Principal principal(String name, String role) {
        return UsernamePasswordAuthenticationToken.authenticated(name, "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }
}
