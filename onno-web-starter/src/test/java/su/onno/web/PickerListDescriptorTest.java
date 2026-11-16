package su.onno.web;

import su.onno.web.pickerdialog.PickerDialogTestApp;

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
 * The "Show all…" picker descriptor (#411): the target's default list columns, stripped of every
 * write affordance, and refused to a caller who cannot read the target.
 */
@SpringBootTest(classes = PickerDialogTestApp.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:picker_dialog;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "onno.scan-packages=su.onno.web.pickerdialog.model",
        "onno.ui.locale=en",
        "spring.main.banner-mode=off"
})
class PickerListDescriptorTest {

    private static final Principal READER = principal("reader@pd.test", "READER");
    private static final Principal WRITER = principal("writer@pd.test", "WRITER");
    private static final Principal OUTSIDER = principal("outsider@pd.test", "OUTSIDER");

    @Autowired WebApplicationContext context;

    private final ObjectMapper json = new ObjectMapper();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void theDefaultColumnsAreServed() throws Exception {
        Map<String, Object> d = descriptor("/api/ui/pickers/catalogs/PdGroups", WRITER);

        assertThat(d).containsEntry("kind", "catalogs").containsEntry("name", "PdGroups")
                .containsEntry("hierarchical", true);
        assertThat(columnNames(d)).contains("_description", columnOf(d, "region"));
    }

    @Test
    void aPickerCarriesNoWriteAffordanceEvenForAWriter() throws Exception {
        Map<String, Object> d = descriptor("/api/ui/pickers/catalogs/PdGroups", WRITER);

        assertThat(d.get("newUrl")).isNull();
        assertThat(d).containsEntry("canWrite", false)
                .containsEntry("commands", List.of())
                .doesNotContainKeys("selectionCheckboxes", "map", "custom", "dynamicCommands");
    }

    @Test
    void everyReadableTargetIsServedWithTheDefaultColumns() throws Exception {
        // There is no view allowlist any more: a readable entity always has its default surface.
        assertThat(mvc.perform(get("/api/ui/view/catalogs/PdTags").principal(READER)).andReturn()
                .getResponse().getStatus()).isEqualTo(200);

        Map<String, Object> d = descriptor("/api/ui/pickers/catalogs/PdTags", READER);

        assertThat(d).containsEntry("hierarchical", false);
        assertThat(columnNames(d)).contains("_description");
    }

    @Test
    void aCallerWhoCannotReadTheTargetIsRefusedAndAnUnknownKindIs404() throws Exception {
        assertThat(status("/api/ui/pickers/catalogs/PdGroups", OUTSIDER)).isEqualTo(403);
        assertThat(status("/api/ui/pickers/registers/PdGroups", READER)).isEqualTo(404);
    }

    private int status(String url, Principal principal) throws Exception {
        return mvc.perform(get(url).principal(principal)).andReturn().getResponse().getStatus();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> descriptor(String url, Principal principal) throws Exception {
        var response = mvc.perform(get(url).principal(principal)).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        return json.readValue(response.getContentAsString(), Map.class);
    }

    @SuppressWarnings("unchecked")
    private static List<String> columnNames(Map<String, Object> d) {
        return ((List<Map<String, Object>>) d.get("columns")).stream()
                .map(c -> String.valueOf(c.get("columnName"))).toList();
    }

    @SuppressWarnings("unchecked")
    private static String columnOf(Map<String, Object> d, String field) {
        return ((List<Map<String, Object>>) d.get("columns")).stream()
                .filter(c -> field.equals(c.get("fieldName")))
                .map(c -> String.valueOf(c.get("columnName"))).findFirst().orElse("?");
    }

    private static Principal principal(String name, String role) {
        return UsernamePasswordAuthenticationToken.authenticated(name, "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }
}
