package com.onec.metadata;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MetadataRegistry {

    private final Map<Class<?>, CatalogDescriptor> catalogs = new ConcurrentHashMap<>();
    private final Map<Class<?>, DocumentDescriptor> documents = new ConcurrentHashMap<>();
    private final Map<Class<?>, AccumulationRegisterDescriptor> registers = new ConcurrentHashMap<>();

    public void register(CatalogDescriptor descriptor) {
        catalogs.put(descriptor.javaClass(), descriptor);
    }

    public void registerDocument(DocumentDescriptor descriptor) {
        documents.put(descriptor.javaClass(), descriptor);
    }

    public CatalogDescriptor getDescriptor(Class<?> clazz) {
        CatalogDescriptor descriptor = catalogs.get(clazz);
        if (descriptor == null) {
            throw new IllegalArgumentException("No descriptor registered for " + clazz.getName());
        }
        return descriptor;
    }

    public DocumentDescriptor getDocumentDescriptor(Class<?> clazz) {
        DocumentDescriptor descriptor = documents.get(clazz);
        if (descriptor == null) {
            throw new IllegalArgumentException("No document descriptor registered for " + clazz.getName());
        }
        return descriptor;
    }

    public Collection<CatalogDescriptor> allCatalogs() {
        return Collections.unmodifiableCollection(catalogs.values());
    }

    public Collection<DocumentDescriptor> allDocuments() {
        return Collections.unmodifiableCollection(documents.values());
    }

    public void registerAccumulation(AccumulationRegisterDescriptor descriptor) {
        registers.put(descriptor.javaClass(), descriptor);
    }

    public AccumulationRegisterDescriptor getRegisterDescriptor(Class<?> clazz) {
        AccumulationRegisterDescriptor descriptor = registers.get(clazz);
        if (descriptor == null) {
            throw new IllegalArgumentException("No register descriptor registered for " + clazz.getName());
        }
        return descriptor;
    }

    public Collection<AccumulationRegisterDescriptor> allRegisters() {
        return Collections.unmodifiableCollection(registers.values());
    }
}
