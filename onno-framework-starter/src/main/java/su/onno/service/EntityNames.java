package su.onno.service;

import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.DocumentDescriptor;
import su.onno.metadata.MetadataRegistry;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Resolves a catalog/document from a decoded name as a stored link spells it: case, spaces and
 * underscores are ignored, and a former {@code previousNames} entry resolves once no current name
 * matches — a task subject or notification written before a rename keeps pointing at its record.
 * Shared by the record-scope gates so they cannot disagree on which entity a link names.
 */
public final class EntityNames {

    private EntityNames() {
    }

    public static CatalogDescriptor catalog(MetadataRegistry registry, String name) {
        return resolve(registry.allCatalogs(), CatalogDescriptor::logicalName, CatalogDescriptor::previousNames, name);
    }

    public static DocumentDescriptor document(MetadataRegistry registry, String name) {
        return resolve(registry.allDocuments(), DocumentDescriptor::logicalName, DocumentDescriptor::previousNames, name);
    }

    /** The class of the catalog ({@code catalogs}) or document ({@code documents}) {@code name}, or {@code null}. */
    static Class<?> recordClass(MetadataRegistry registry, String kind, String name) {
        if ("catalogs".equals(kind)) {
            CatalogDescriptor d = catalog(registry, name);
            return d == null ? null : d.javaClass();
        }
        if ("documents".equals(kind)) {
            DocumentDescriptor d = document(registry, name);
            return d == null ? null : d.javaClass();
        }
        return null;
    }

    private static <D> D resolve(Collection<D> all, Function<D, String> logicalName,
                                 Function<D, List<String>> previousNames, String name) {
        if (name == null) return null;
        String key = key(name);
        for (D d : all) {
            if (key(logicalName.apply(d)).equals(key)) return d;
        }
        for (D d : all) {
            List<String> former = previousNames.apply(d);
            if (former == null) continue;
            for (String previous : former) {
                if (key(previous).equals(key)) return d;
            }
        }
        return null;
    }

    private static String key(String name) {
        return name.replace(" ", "").replace("_", "").toLowerCase(Locale.ROOT);
    }
}
