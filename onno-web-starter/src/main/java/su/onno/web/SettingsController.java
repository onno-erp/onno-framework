package su.onno.web;

import su.onno.metadata.ConstantDescriptor;
import su.onno.metadata.MetadataRegistry;
import su.onno.repository.ConstantManager;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.security.Principal;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * App settings backed by the framework's {@link su.onno.annotations.Constant}s. {@code GET} lists
 * every registered constant as {@code {name, type, value}} (the Java value type's simple name — the
 * client picks the control); {@code PUT} persists changes via {@link ConstantManager}. Admin-gated
 * — settings are app-wide.
 */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final MetadataRegistry registry;
    private final ConstantManager constants;
    private final AccessService access;

    public SettingsController(MetadataRegistry registry, ConstantManager constants, AccessService access) {
        this.registry = registry;
        this.constants = constants;
        this.access = access;
    }

    @GetMapping
    public List<Map<String, Object>> list(Principal principal) {
        requireAdmin(principal);
        if (registry.allConstants().size() > 500) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "Settings exceed the 500-item metadata limit");
        return registry.allConstants().stream()
                .sorted(Comparator.comparing(ConstantDescriptor::logicalName))
                .map(d -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", d.logicalName());
                    m.put("type", d.valueType().getSimpleName());
                    m.put("value", constants.get(d.javaClass()));
                    return m;
                })
                .toList();
    }

    @PutMapping
    public void update(@RequestBody Map<String, Object> body, Principal principal) {
        requireAdmin(principal);
        Map<String, ConstantDescriptor> byName = new LinkedHashMap<>();
        for (ConstantDescriptor d : registry.allConstants()) {
            byName.put(d.logicalName(), d);
        }
        body.forEach((name, value) -> {
            ConstantDescriptor d = byName.get(name);
            if (d != null) {
                constants.set(d.javaClass(), coerce(d.valueType(), value));
            }
        });
    }

    private void requireAdmin(Principal principal) {
        if (!access.roles(principal).contains("ADMIN")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Settings are administrator-only");
        }
    }

    private static boolean isBoolean(Class<?> type) {
        return type == boolean.class || type == Boolean.class;
    }

    /** Coerce a JSON body value to the constant's declared type before persisting. */
    private static Object coerce(Class<?> type, Object v) {
        if (v == null) {
            return null;
        }
        String s = v.toString().trim();
        if (isBoolean(type)) {
            return v instanceof Boolean b ? b : Boolean.parseBoolean(s);
        }
        if (type == Integer.class || type == int.class) {
            return v instanceof Number n ? n.intValue() : Integer.parseInt(s);
        }
        if (type == Long.class || type == long.class) {
            return v instanceof Number n ? n.longValue() : Long.parseLong(s);
        }
        if (type == BigDecimal.class) {
            return v instanceof BigDecimal b ? b : new BigDecimal(s);
        }
        return v.toString();
    }
}
