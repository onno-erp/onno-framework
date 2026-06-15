package com.onec.ui;

import com.onec.annotations.Attribute;
import com.onec.annotations.Catalog;
import com.onec.metadata.DefaultNamingStrategy;
import com.onec.metadata.MetadataRegistry;
import com.onec.metadata.MetadataScanner;
import com.onec.model.CatalogObject;
import com.onec.types.Ref;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * {@link ResolvedMetadataService#describeCatalog} resolves a view's declared related-list panels
 * against the join catalog's scanned metadata — turning {@code via}/{@code display} field names
 * into the columns + target the form widget needs, and dropping panels that don't resolve.
 */
class RelatedListMetadataTest {

    @Catalog(name = "RlClinics")
    static class Clinic extends CatalogObject {
    }

    @Catalog(name = "RlDoctors")
    static class Doctor extends CatalogObject {
    }

    @Catalog(name = "RlClinicDoctor")
    static class ClinicDoctor extends CatalogObject {
        @Attribute
        private Ref<Clinic> clinic;
        @Attribute
        private Ref<Doctor> doctor;
        @Attribute(length = 60)
        private String role;
    }

    static class ClinicView implements EntityView {
        @Override
        public Class<?> entity() {
            return Clinic.class;
        }

        @Override
        public void fields(EntityConfigBuilder f) {
            f.relatedList("doctors", ClinicDoctor.class)
                    .via("clinic").display("doctor").columns("doctor", "role").label("Doctors");
        }
    }

    private ResolvedMetadataService serviceWith(EntityView... views) {
        MetadataRegistry registry = new MetadataRegistry();
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        registry.registerCatalog(scanner.scan(Clinic.class));
        registry.registerCatalog(scanner.scan(Doctor.class));
        registry.registerCatalog(scanner.scan(ClinicDoctor.class));
        return new ResolvedMetadataService(registry, new FieldHintResolver(List.of(views)));
    }

    @SuppressWarnings("unchecked")
    @Test
    void describeCatalog_resolvesRelatedListPanel() {
        ResolvedMetadataService svc = serviceWith(new ClinicView());
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());

        Map<String, Object> described = svc.describeCatalog(scanner.scan(Clinic.class));
        List<Map<String, Object>> related = (List<Map<String, Object>>) described.get("relatedLists");

        assertThat(related).hasSize(1);
        Map<String, Object> rl = related.get(0);
        assertThat(rl.get("name")).isEqualTo("doctors");
        assertThat(rl.get("label")).isEqualTo("Doctors");
        assertThat(rl.get("joinCatalog")).isEqualTo("RlClinicDoctor");
        assertThat(rl.get("viaField")).isEqualTo("clinic");
        assertThat(rl.get("displayField")).isEqualTo("doctor");
        assertThat(rl.get("target")).isEqualTo("RlDoctors");
        assertThat(rl.get("targetKind")).isEqualTo("catalog");
        // Defaults to visible in the detail view, so the panel lights up read-only there too.
        assertThat(rl.get("showInDetail")).isEqualTo(true);

        List<Map<String, Object>> columns = (List<Map<String, Object>>) rl.get("columns");
        assertThat(columns).extracting(c -> c.get("fieldName")).containsExactly("doctor", "role");
        // The display ref column carries its target so the row renders a resolved label.
        Map<String, Object> doctorCol = columns.get(0);
        assertThat(doctorCol.get("isRef")).isEqualTo(true);
        assertThat(doctorCol.get("refTarget")).isEqualTo("RlDoctors");
    }

    @SuppressWarnings("unchecked")
    @Test
    void describeCatalog_hideInDetail_marksPanelHiddenFromDetail() {
        ResolvedMetadataService svc = serviceWith(new EntityView() {
            @Override
            public Class<?> entity() {
                return Clinic.class;
            }

            @Override
            public void fields(EntityConfigBuilder f) {
                f.relatedList("doctors", ClinicDoctor.class)
                        .via("clinic").display("doctor").hideInDetail();
            }
        });
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());

        Map<String, Object> described = svc.describeCatalog(scanner.scan(Clinic.class));
        List<Map<String, Object>> related = (List<Map<String, Object>>) described.get("relatedLists");
        assertThat(related).hasSize(1);
        assertThat(related.get(0).get("showInDetail")).isEqualTo(false);
    }

    @SuppressWarnings("unchecked")
    @Test
    void describeCatalog_noView_emitsEmptyRelatedLists() {
        ResolvedMetadataService svc = serviceWith();
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());

        Map<String, Object> described = svc.describeCatalog(scanner.scan(Clinic.class));
        assertThat((List<Map<String, Object>>) described.get("relatedLists")).isEmpty();
    }

    @SuppressWarnings("unchecked")
    @Test
    void describeCatalog_dropsPanelWithUnknownViaField() {
        ResolvedMetadataService svc = serviceWith(new EntityView() {
            @Override
            public Class<?> entity() {
                return Clinic.class;
            }

            @Override
            public void fields(EntityConfigBuilder f) {
                // "owner" is not a ref on the join catalog — the panel can't resolve and is dropped.
                f.relatedList("doctors", ClinicDoctor.class).via("owner").display("doctor");
            }
        });
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());

        Map<String, Object> described = svc.describeCatalog(scanner.scan(Clinic.class));
        assertThat((List<Map<String, Object>>) described.get("relatedLists")).isEmpty();
    }

    @SuppressWarnings("unchecked")
    @Test
    void describeCatalog_explicitColumnsAlwaysIncludeDisplayRef() {
        // The panel wants the join-row "role" column but forgets to list the display ref. The
        // display ref is the row's primary identity, so it must still render (prepended) — an
        // explicit list adds columns on top of the name rather than replacing it (see #108).
        ResolvedMetadataService svc = serviceWith(new EntityView() {
            @Override
            public Class<?> entity() {
                return Clinic.class;
            }

            @Override
            public void fields(EntityConfigBuilder f) {
                f.relatedList("doctors", ClinicDoctor.class)
                        .via("clinic").display("doctor").columns("role");
            }
        });
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());

        Map<String, Object> described = svc.describeCatalog(scanner.scan(Clinic.class));
        List<Map<String, Object>> related = (List<Map<String, Object>>) described.get("relatedLists");
        assertThat(related).hasSize(1);
        List<Map<String, Object>> columns = (List<Map<String, Object>>) related.get(0).get("columns");
        // Display ref prepended ahead of the explicit "role" column.
        assertThat(columns).extracting(c -> c.get("fieldName")).containsExactly("doctor", "role");
    }

    @SuppressWarnings("unchecked")
    @Test
    void describeCatalog_dropsUnknownColumnButKeepsDisplayRef() {
        // "priceFrom" is not an attribute on the join catalog (a typo, or a field that lives on a
        // different catalog). It is dropped (with a WARN), but the panel must not collapse to
        // blank rows — the display ref still renders (see #108).
        ResolvedMetadataService svc = serviceWith(new EntityView() {
            @Override
            public Class<?> entity() {
                return Clinic.class;
            }

            @Override
            public void fields(EntityConfigBuilder f) {
                f.relatedList("doctors", ClinicDoctor.class)
                        .via("clinic").display("doctor").columns("priceFrom");
            }
        });
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());

        Map<String, Object> described = svc.describeCatalog(scanner.scan(Clinic.class));
        List<Map<String, Object>> related = (List<Map<String, Object>>) described.get("relatedLists");
        assertThat(related).hasSize(1);
        List<Map<String, Object>> columns = (List<Map<String, Object>>) related.get(0).get("columns");
        // Bogus column dropped; the display ref remains so the row keeps its name.
        assertThat(columns).extracting(c -> c.get("fieldName")).containsExactly("doctor");
    }
}
