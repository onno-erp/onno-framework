package su.onno.web;

import su.onno.access.AccessSubject;
import su.onno.metadata.AttributeDescriptor;
import su.onno.metadata.InformationRegisterDescriptor;
import su.onno.metadata.MetadataRegistry;
import su.onno.model.Periodicity;
import su.onno.service.AccessService;
import su.onno.service.EntityJsonRepresentation;
import su.onno.service.InformationRegisterQueryService;
import su.onno.service.ListFilter;
import su.onno.service.ServiceException;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Read-only information-register surfaces and their scoped, paged grid feed. */
@RestController
public class InformationRegisterListController {
    private final MetadataRegistry registry;
    private final InformationRegisterQueryService query;
    private final AccessService access;
    private final ResolvedMetadataService metadata;
    private final UiMessages messages;

    public InformationRegisterListController(MetadataRegistry registry, InformationRegisterQueryService query,
            AccessService access, ResolvedMetadataService metadata, UiMessages messages) {
        this.registry = registry;
        this.query = query;
        this.access = access;
        this.metadata = metadata;
        this.messages = messages;
    }

    @GetMapping("/api/ui/view/information-registers/{name}")
    public Map<String, Object> view(@PathVariable String name, AccessSubject subject) {
        var desc = require(name);
        access.requireRead(subject, desc);
        boolean periodic = desc.periodicity() != Periodicity.NONE;
        List<Map<String, Object>> columns = new ArrayList<>();
        if (periodic) {
            columns.add(Map.of("columnName", "_period", "fieldName", "period", "label", messages.get("register.date"), "type", "date"));
        }
        for (var field : metadata.describeAttributes(fields(desc))) {
            Map<String, Object> column = new LinkedHashMap<>();
            column.put("columnName", field.get("columnName"));
            column.put("fieldName", field.get("fieldName"));
            column.put("label", field.get("displayName"));
            column.put("type", field.get("isRef").equals(true) ? "ref" : fieldType(String.valueOf(field.get("javaType"))));
            columns.add(column);
        }
        Map<String, Object> list = new LinkedHashMap<>();
        // The shared grid's register kind disables record links, row writes, grouping and calendars.
        list.put("kind", "registers");
        list.put("name", name);
        list.put("title", desc.displayTitle());
        list.put("columns", columns);
        list.put("searchable", false);
        list.put("sort", Map.of("column", periodic ? "_period" : "_id", "descending", true));
        List<Map<String, Object>> filters = new ArrayList<>();
        if (periodic) filters.add(Map.of("key", "period", "column", "_period", "label", messages.get("register.date"), "type", "dateRange", "options", List.of()));
        list.put("filters", filters);
        list.put("newUrl", null);
        list.put("canWrite", false);
        list.put("commands", List.of());
        list.put("pageSize", 50);
        list.put("feed", "/api/list/information-registers/" + name);
        return Map.of("informationRegister", Map.of("periodic", periodic, "list", list));
    }

    @GetMapping("/api/list/information-registers/{name}")
    public Map<String, Object> rows(@PathVariable String name,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "desc") String dir,
            @RequestParam(required = false) String where,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime at,
            HttpServletRequest request, AccessSubject subject) {
        var desc = require(name);
        access.requireRead(subject, desc);
        var fields = fields(desc);
        Set<String> columns = fields.stream().map(AttributeDescriptor::columnName).collect(Collectors.toSet());
        columns.add("_id");
        if (desc.periodicity() != Periodicity.NONE) columns.add("_period");
        Set<String> numeric = fields.stream().filter(f -> Number.class.isAssignableFrom(f.javaType())
                || f.javaType().isPrimitive() && f.javaType() != boolean.class && f.javaType() != char.class)
                .map(AttributeDescriptor::columnName).collect(Collectors.toSet());
        var filters = ListFilter.parse(multi(request, "eq"), multi(request, "in"), multi(request, "like"),
                multi(request, "prefix"), multi(request, "ge"), multi(request, "le"), columns, numeric);
        var page = query.listPage(subject, desc, filters, where, sort, !"asc".equalsIgnoreCase(dir), at,
                request.getParameter("cursor"), limit);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", EntityJsonRepresentation.informationRegisters(desc, page.rows()));
        out.put("nextCursor", page.nextCursor());
        out.put("hasMore", page.hasMore());
        if ("exact".equals(request.getParameter("count"))) out.put("total", query.listCount(subject, desc, filters, where, at));
        return out;
    }

    private InformationRegisterDescriptor require(String name) {
        return registry.allInformationRegisters().stream()
                .filter(d -> d.logicalName().equalsIgnoreCase(name) || routeName(d.logicalName()).equals(name))
                .findFirst().orElseThrow(() -> new ServiceException(ServiceException.Status.NOT_FOUND,
                        "Information register not found: " + name));
    }

    private static String routeName(String name) {
        return name.replace(" ", "").replaceAll("(?<!^)([A-Z])", "_$1").toLowerCase(java.util.Locale.ROOT);
    }

    private static List<AttributeDescriptor> fields(InformationRegisterDescriptor desc) {
        List<AttributeDescriptor> fields = new ArrayList<>(desc.dimensions());
        fields.addAll(desc.resources());
        fields.addAll(desc.attributes());
        return fields.stream().filter(f -> !f.secret()).toList();
    }

    private static String fieldType(String type) {
        return switch (type) {
            case "BigDecimal", "BigInteger", "Integer", "Long", "Short", "Byte", "Double", "Float", "int", "long", "short", "byte", "double", "float" -> "number";
            case "LocalDate", "LocalDateTime", "Instant" -> "date";
            case "Boolean", "boolean" -> "boolean";
            default -> "text";
        };
    }

    private static List<String> multi(HttpServletRequest request, String key) {
        String[] values = request.getParameterValues(key);
        return values == null ? List.of() : Arrays.asList(values);
    }
}
