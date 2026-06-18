package su.onno.print;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class PrintTemplateRegistry {

    private final Map<Class<?>, Map<String, PrintTemplateDescriptor>> byTarget = new LinkedHashMap<>();

    public void register(PrintTemplateDescriptor descriptor) {
        byTarget.computeIfAbsent(descriptor.target(), k -> new LinkedHashMap<>())
                .put(descriptor.name(), descriptor);
    }

    public List<PrintTemplateDescriptor> forTarget(Class<?> target) {
        Map<String, PrintTemplateDescriptor> map = byTarget.get(target);
        if (map == null) {
            return List.of();
        }
        List<PrintTemplateDescriptor> list = new ArrayList<>(map.values());
        list.sort((a, b) -> Integer.compare(a.order(), b.order()));
        return Collections.unmodifiableList(list);
    }

    public Optional<PrintTemplateDescriptor> find(Class<?> target, String name) {
        return Optional.ofNullable(byTarget.getOrDefault(target, Map.of()).get(name));
    }

    public List<PrintTemplateDescriptor> all() {
        List<PrintTemplateDescriptor> all = new ArrayList<>();
        byTarget.values().forEach(m -> all.addAll(m.values()));
        return Collections.unmodifiableList(all);
    }
}
