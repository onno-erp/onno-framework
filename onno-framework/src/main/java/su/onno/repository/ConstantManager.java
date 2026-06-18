package su.onno.repository;

import su.onno.metadata.ConstantDescriptor;
import su.onno.metadata.MetadataRegistry;

public class ConstantManager {

    private final ConstantPersistence persistence;
    private final MetadataRegistry registry;

    public ConstantManager(ConstantPersistence persistence, MetadataRegistry registry) {
        this.persistence = persistence;
        this.registry = registry;
    }

    @SuppressWarnings("unchecked")
    public <T> T get(Class<?> constantClass) {
        ConstantDescriptor descriptor = registry.getConstantDescriptor(constantClass);
        return (T) persistence.get(descriptor);
    }

    public <T> void set(Class<?> constantClass, T value) {
        ConstantDescriptor descriptor = registry.getConstantDescriptor(constantClass);
        persistence.set(descriptor, value);
    }
}
