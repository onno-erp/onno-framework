package su.onno.mail.template;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class MailTemplateRegistry {

    private final Map<Class<?>, Map<String, MailTemplateDescriptor>> byTarget = new LinkedHashMap<>();

    public void register(MailTemplateDescriptor descriptor) {
        byTarget.computeIfAbsent(descriptor.target(), k -> new LinkedHashMap<>())
                .put(descriptor.name(), descriptor);
    }

    public Optional<MailTemplateDescriptor> find(Class<?> target, String name) {
        return Optional.ofNullable(byTarget.getOrDefault(target, Map.of()).get(name));
    }

    /** Looks up a template by name across all targets. Used by the preview endpoint. */
    public Optional<MailTemplateDescriptor> findByName(String name) {
        return all().stream().filter(d -> d.name().equals(name)).findFirst();
    }

    /** All registered descriptors, in registration order. */
    public List<MailTemplateDescriptor> all() {
        List<MailTemplateDescriptor> out = new ArrayList<>();
        byTarget.values().forEach(m -> out.addAll(m.values()));
        return out;
    }
}
