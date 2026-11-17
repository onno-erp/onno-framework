package su.onno.web.relations;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import su.onno.metadata.AttributeDescriptor;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.MetadataRegistry;
import su.onno.web.relations.model.RlClinic;
import su.onno.web.relations.model.RlClinicDoctor;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * A record saved with its join-entity relations ({@code @relations}): the row set is replaced in the
 * record's transaction, through the generic command path, and only the record's own rows can be
 * touched.
 */
@SpringBootTest(classes = RelationTestApp.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:relations;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "onno.scan-packages=su.onno.web.relations.model",
        "onno.ui.locale=en",
        "spring.main.banner-mode=off"
})
class RelationWriteTest {

    private static final Principal WRITER = UsernamePasswordAuthenticationToken.authenticated(
            "writer@rl.test", "n/a", List.of(new SimpleGrantedAuthority("ROLE_WRITER")));

    private static final Principal LIMITED = UsernamePasswordAuthenticationToken.authenticated(
            "limited@rl.test", "n/a", List.of(new SimpleGrantedAuthority("ROLE_LIMITED")));

    @Autowired WebApplicationContext context;
    @Autowired Jdbi jdbi;
    @Autowired MetadataRegistry registry;

    private final ObjectMapper json = new ObjectMapper();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void aNewRecordIsCreatedWithItsRelationRows() throws Exception {
        String anna = doctor("Anna");
        String jon = doctor("Jon");

        Map<String, Object> clinic = send(post("/api/catalogs/RlClinics"), Map.of(
                "description", "North",
                "@relations", List.of(relation(List.of(
                        Map.of("doctor", anna, "role", "Lead"),
                        Map.of("doctor", jon))))), 200);

        UUID id = UUID.fromString(String.valueOf(clinic.get("id")));
        assertThat(rows(id)).extracting(r -> r.get("doctor") + "/" + r.get("role"))
                .containsExactlyInAnyOrder(anna + "/Lead", jon + "/null");
    }

    @Test
    void anUpdateReplacesTheRowSet() throws Exception {
        String anna = doctor("Anna");
        String jon = doctor("Jon");
        String mia = doctor("Mia");
        UUID clinic = clinicWith(List.of(Map.of("doctor", anna, "role", "Lead"), Map.of("doctor", jon)));
        Map<UUID, Map<String, Object>> before = byDoctor(clinic);
        UUID annaRow = rowFor(before, anna);

        send(put("/api/catalogs/RlClinics/" + clinic), Map.of(
                "city", "Oslo",
                "@relations", List.of(relation(List.of(
                        Map.of("id", annaRow.toString(), "doctor", anna, "role", "Chief"),
                        Map.of("doctor", mia, "role", "Surgeon"))))), 200);

        assertThat(rows(clinic)).extracting(r -> r.get("doctor") + "/" + r.get("role"))
                .containsExactlyInAnyOrder(anna + "/Chief", mia + "/Surgeon");
        assertThat(rowFor(byDoctor(clinic), anna)).isEqualTo(annaRow);
        assertThat(deletionMarked(rowFor(before, jon))).isTrue();
    }

    @Test
    void aRowOfAnotherRecordIsRejectedAndNothingChanges() throws Exception {
        String anna = doctor("Anna");
        UUID other = clinicWith(List.of(Map.of("doctor", anna)));
        UUID foreignRow = rows(other).get(0).get("id") instanceof UUID u ? u
                : UUID.fromString(String.valueOf(rows(other).get(0).get("id")));
        UUID clinic = clinicWith(List.of());

        send(put("/api/catalogs/RlClinics/" + clinic), Map.of(
                "city", "Bergen",
                "@relations", List.of(relation(List.of(
                        Map.of("id", foreignRow.toString(), "doctor", anna))))), 400);

        assertThat(city(clinic)).isNull();
        assertThat(rows(other)).hasSize(1);
        assertThat(rows(clinic)).isEmpty();
    }

    @Test
    void aFailingRowRollsBackTheRecord() throws Exception {
        String anna = doctor("Anna");
        String jon = doctor("Jon");
        UUID clinic = clinicWith(List.of(Map.of("doctor", anna)));

        int status = mvc.perform(json(put("/api/catalogs/RlClinics/" + clinic), Map.of(
                "city", "Tromso",
                "@relations", List.of(relation(List.of(
                        Map.of("doctor", jon, "role", "forbidden"))))))).andReturn().getResponse().getStatus();

        assertThat(status).isGreaterThanOrEqualTo(400);
        assertThat(city(clinic)).isNull();
        assertThat(rows(clinic)).extracting(r -> String.valueOf(r.get("doctor"))).containsExactly(anna);
    }

    @Test
    void viaMustReferenceTheRecord() throws Exception {
        String anna = doctor("Anna");
        Map<String, Object> wrongVia = new LinkedHashMap<>(relation(List.of(Map.of("clinic", anna))));
        wrongVia.put("via", "doctor");

        send(post("/api/catalogs/RlClinics"), Map.of("description", "South", "@relations", List.of(wrongVia)), 400);
    }

    @Test
    void linksHiddenFromTheCallerAreLeftAlone() throws Exception {
        String anna = doctor("Anna");
        String jon = doctor("Jon");
        UUID clinic = clinicWith(List.of(Map.of("doctor", anna, "role", "visible"), Map.of("doctor", jon, "role", "hidden")));

        // The limited user's form lists only Anna's link; clearing it must not touch Jon's.
        var response = mvc.perform(put("/api/catalogs/RlClinics/" + clinic).principal(LIMITED)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("@relations", List.of(relation(List.of()))))))
                .andReturn().getResponse();

        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        assertThat(rows(clinic)).extracting(r -> r.get("doctor") + "/" + r.get("role"))
                .containsExactly(jon + "/hidden");
    }

    // ---------------------------------------------------------------- helpers

    private static Map<String, Object> relation(List<Map<String, Object>> rows) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("entity", "RlClinicDoctors");
        r.put("via", "clinic");
        r.put("rows", rows);
        return r;
    }

    private String doctor(String name) throws Exception {
        return String.valueOf(send(post("/api/catalogs/RlDoctors"), Map.of("description", name), 200).get("id"));
    }

    private UUID clinicWith(List<Map<String, Object>> rows) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("description", "Clinic " + UUID.randomUUID());
        body.put("@relations", List.of(relation(rows)));
        return UUID.fromString(String.valueOf(send(post("/api/catalogs/RlClinics"), body, 200).get("id")));
    }

    private Map<UUID, Map<String, Object>> byDoctor(UUID clinic) {
        Map<UUID, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map<String, Object> r : rows(clinic)) out.put(UUID.fromString(String.valueOf(r.get("id"))), r);
        return out;
    }

    private static UUID rowFor(Map<UUID, Map<String, Object>> rows, String doctor) {
        return rows.entrySet().stream().filter(e -> doctor.equals(String.valueOf(e.getValue().get("doctor"))))
                .map(Map.Entry::getKey).findFirst().orElseThrow();
    }

    /** The live join rows of a clinic as {id, doctor, role}. */
    private List<Map<String, Object>> rows(UUID clinic) {
        CatalogDescriptor d = registry.getCatalogDescriptor(RlClinicDoctor.class);
        return jdbi.withHandle(h -> h.createQuery("SELECT _id, " + column(d, "doctor") + " AS doctor, "
                        + column(d, "role") + " AS role FROM " + d.tableName() + " WHERE " + column(d, "clinic")
                        + " = :clinic AND _deletion_mark = false")
                .bind("clinic", clinic)
                .map((rs, ctx) -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("id", rs.getObject("_id"));
                    r.put("doctor", String.valueOf(rs.getObject("doctor")));
                    r.put("role", rs.getString("role"));
                    return r;
                })
                .list());
    }

    private boolean deletionMarked(UUID row) {
        CatalogDescriptor d = registry.getCatalogDescriptor(RlClinicDoctor.class);
        return jdbi.withHandle(h -> h.createQuery("SELECT _deletion_mark FROM " + d.tableName() + " WHERE _id = :id")
                .bind("id", row).mapTo(Boolean.class).one());
    }

    private String city(UUID clinic) {
        CatalogDescriptor d = registry.getCatalogDescriptor(RlClinic.class);
        return jdbi.withHandle(h -> h.createQuery("SELECT " + column(d, "city") + " FROM " + d.tableName()
                        + " WHERE _id = :id")
                .bind("id", clinic).mapTo(String.class).one());
    }

    private static String column(CatalogDescriptor d, String field) {
        return d.attributes().stream().filter(a -> a.fieldName().equals(field))
                .map(AttributeDescriptor::columnName).findFirst().orElseThrow();
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, Map<String, Object> body)
            throws Exception {
        return request.principal(WRITER).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> send(MockHttpServletRequestBuilder request, Map<String, Object> body, int expected)
            throws Exception {
        var response = mvc.perform(json(request, body)).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(expected);
        String content = response.getContentAsString();
        return content.isBlank() ? Map.of() : json.readValue(content, Map.class);
    }
}
