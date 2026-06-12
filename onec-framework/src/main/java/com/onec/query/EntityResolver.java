package com.onec.query;

import com.onec.metadata.AccumulationRegisterDescriptor;
import com.onec.metadata.AttributeDescriptor;
import com.onec.metadata.CatalogDescriptor;
import com.onec.metadata.DocumentDescriptor;
import com.onec.metadata.InformationRegisterDescriptor;
import com.onec.metadata.MetadataRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bridges the query layer to {@link MetadataRegistry}: resolves a Java entity class, or
 * the logical name a {@code Ref} attribute targets, to a uniform {@link EntityMeta}.
 * This is the only place that knows how the per-kind descriptors map onto table names,
 * primary keys, and column sets.
 *
 * <p>Lookups are cached: the registry is fully populated by the startup scanners before
 * any query runs, so a linear descriptor scan per query (and per auto-join) would be
 * pure overhead.
 */
final class EntityResolver {

    private final MetadataRegistry registry;
    private final ConcurrentHashMap<Class<?>, EntityMeta> byClass = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Optional<EntityMeta>> byRefTarget = new ConcurrentHashMap<>();

    EntityResolver(MetadataRegistry registry) {
        this.registry = registry;
    }

    /** Resolve a queryable entity by its Java class, or throw if it is not registered. */
    EntityMeta forClass(Class<?> type) {
        return byClass.computeIfAbsent(type, this::scanForClass);
    }

    private EntityMeta scanForClass(Class<?> type) {
        for (CatalogDescriptor d : registry.allCatalogs()) {
            if (d.javaClass() == type) return catalog(d);
        }
        for (DocumentDescriptor d : registry.allDocuments()) {
            if (d.javaClass() == type) return document(d);
        }
        for (AccumulationRegisterDescriptor d : registry.allRegisters()) {
            if (d.javaClass() == type) return accumulation(d);
        }
        for (InformationRegisterDescriptor d : registry.allInformationRegisters()) {
            if (d.javaClass() == type) return information(d);
        }
        throw new IllegalArgumentException(
                "No catalog/document/register metadata registered for " + type.getName());
    }

    /**
     * Resolve the entity a {@code Ref} attribute points at. {@code refTarget} is the
     * <em>logical</em> name stored on the attribute (e.g. {@code "Clients"}), matched
     * against catalog and document logical names.
     */
    EntityMeta forRefTarget(String refTarget) {
        if (refTarget == null) {
            return null;
        }
        return byRefTarget.computeIfAbsent(refTarget, this::scanForRefTarget).orElse(null);
    }

    private Optional<EntityMeta> scanForRefTarget(String refTarget) {
        for (CatalogDescriptor d : registry.allCatalogs()) {
            if (d.logicalName().equals(refTarget)) return Optional.of(catalog(d));
        }
        for (DocumentDescriptor d : registry.allDocuments()) {
            if (d.logicalName().equals(refTarget)) return Optional.of(document(d));
        }
        return Optional.empty();
    }

    private static EntityMeta catalog(CatalogDescriptor d) {
        return new EntityMeta(EntityMeta.Kind.CATALOG, d.javaClass(), d.tableName(), "_id",
                EntityMeta.catalogSystemColumns(), d.attributes());
    }

    private static EntityMeta document(DocumentDescriptor d) {
        return new EntityMeta(EntityMeta.Kind.DOCUMENT, d.javaClass(), d.tableName(), "_id",
                EntityMeta.documentSystemColumns(), d.attributes());
    }

    private static EntityMeta accumulation(AccumulationRegisterDescriptor d) {
        List<AttributeDescriptor> attrs = new ArrayList<>(d.dimensions());
        attrs.addAll(d.resources());
        return new EntityMeta(EntityMeta.Kind.ACCUMULATION_REGISTER, d.javaClass(), d.tableName(), "_id",
                EntityMeta.registerSystemColumns(), attrs);
    }

    private static EntityMeta information(InformationRegisterDescriptor d) {
        List<AttributeDescriptor> attrs = new ArrayList<>(d.dimensions());
        attrs.addAll(d.resources());
        attrs.addAll(d.attributes());
        return new EntityMeta(EntityMeta.Kind.INFORMATION_REGISTER, d.javaClass(), d.tableName(), "_id",
                EntityMeta.registerSystemColumns(), attrs);
    }
}
