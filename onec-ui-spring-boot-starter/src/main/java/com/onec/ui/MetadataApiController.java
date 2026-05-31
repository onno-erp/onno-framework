package com.onec.ui;

import com.onec.metadata.*;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ui/metadata")
public class MetadataApiController {

    private final MetadataRegistry registry;
    private final UiLayout uiLayout;
    private final UiLayoutResolver layoutResolver;
    private final UiAccessService access;
    private final ResolvedMetadataService resolvedMetadata;

    public MetadataApiController(MetadataRegistry registry,
                                  UiLayout uiLayout,
                                  UiLayoutResolver layoutResolver,
                                  UiAccessService access,
                                  ResolvedMetadataService resolvedMetadata) {
        this.registry = registry;
        this.uiLayout = uiLayout;
        this.layoutResolver = layoutResolver;
        this.access = access;
        this.resolvedMetadata = resolvedMetadata;
    }

    @GetMapping("/layout")
    public List<UiLayout.ResolvedSection> layout(Principal principal) {
        return layoutResolver.resolve(uiLayout).stream()
                .map(section -> new UiLayout.ResolvedSection(
                        section.name(),
                        section.order(),
                        section.icon(),
                        section.placement(),
                        section.items().stream()
                                .filter(item -> access.canRead(principal, item.type(), item.name()))
                                .toList()))
                .filter(section -> !section.items().isEmpty())
                .toList();
    }

    @GetMapping("/catalogs")
    public List<Map<String, Object>> catalogs(Principal principal) {
        return registry.allCatalogs().stream()
                .filter(d -> access.canRead(principal, d))
                .map(resolvedMetadata::describeCatalog)
                .toList();
    }

    @GetMapping("/documents")
    public List<Map<String, Object>> documents(Principal principal) {
        return registry.allDocuments().stream()
                .filter(d -> access.canRead(principal, d))
                .map(resolvedMetadata::describeDocument)
                .toList();
    }

    @GetMapping("/dashboard")
    public List<Map<String, Object>> dashboard(Principal principal) {
        return layoutResolver.resolveWidgets(uiLayout).stream()
                .filter(w -> access.canRead(principal, w.entityType(), w.entityName()))
                .map(w -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("title", w.title());
                    map.put("widgetType", w.widgetType());
                    map.put("order", w.order());
                    map.put("width", w.width());
                    map.put("entityType", w.entityType());
                    map.put("entityName", w.entityName());
                    map.put("maxItems", w.maxItems());
                    map.put("dateField", w.dateField());
                    map.put("titleField", w.titleField());
                    map.put("extraConfig", w.extraConfig());
                    return map;
                })
                .toList();
    }

    @GetMapping("/manifest")
    public BusinessModelManifest manifest(Principal principal) {
        // Widgets are resolved from the configurer (UiLayoutBuilder) so the manifest
        // stays consistent with /api/ui/metadata/dashboard. The resolver falls back
        // to @DashboardWidget when no builder widgets are declared.
        BusinessModelManifest manifest = new BusinessModelManifestBuilder(registry)
                .build(layoutResolver.resolveWidgets(uiLayout));
        return new BusinessModelManifest(
                manifest.schemaVersion(),
                manifest.catalogs().stream()
                        .filter(c -> access.canRead(principal, "catalog", c.name()))
                        .toList(),
                manifest.documents().stream()
                        .filter(d -> access.canRead(principal, "document", d.name()))
                        .toList(),
                manifest.accumulationRegisters().stream()
                        .filter(r -> access.canRead(principal, "register", r.name()))
                        .toList(),
                manifest.informationRegisters().stream()
                        .filter(r -> registry.allInformationRegisters().stream()
                                .filter(desc -> desc.logicalName().equals(r.name()))
                                .anyMatch(desc -> access.canRead(principal, desc)))
                        .toList(),
                manifest.enumerations(),
                manifest.constants(),
                manifest.dashboardWidgets().stream()
                        .filter(w -> access.canRead(principal, w.entityType(), w.entityName()))
                        .toList());
    }

    @GetMapping("/registers")
    public List<Map<String, Object>> registers(Principal principal) {
        return registry.allRegisters().stream()
                .filter(d -> access.canRead(principal, d))
                .map(resolvedMetadata::describeRegister)
                .toList();
    }
}
