package su.onno.web;

import su.onno.service.AccessService;
import su.onno.service.CatalogQueryService;
import su.onno.service.DocumentQueryService;
import su.onno.validation.CheckRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import su.onno.access.AccessMode;
import su.onno.access.AccessSubject;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.DocumentDescriptor;
import su.onno.validation.CheckContext;
import su.onno.validation.CheckDefinition;
import su.onno.validation.Feedback;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Runs one live {@link su.onno.validation.Checks check} against unsaved values — a dry run,
 * nothing persists. The caller must be able to write the entity and, for an existing record, the
 * record must be inside its write scope.
 *
 * <p>{@code POST /api/checks/{kind}/{name}/{key}} with {@code {"id": uuid?, "values": {...}}}
 * answers {@code [{severity, field, message}]}.</p>
 */
@RestController
@RequestMapping("/api/checks")
public final class CheckController {
    private final CatalogQueryService catalogs;
    private final DocumentQueryService documents;
    private final AccessService access;
    private final CheckRegistry checks;

    public CheckController(CatalogQueryService catalogs,
                           DocumentQueryService documents,
                           AccessService access,
                           CheckRegistry checks) {
        this.catalogs = catalogs;
        this.documents = documents;
        this.access = access;
        this.checks = checks;
    }

    @PostMapping("/{kind}/{name}/{key}")
    public List<Feedback> check(@PathVariable String kind,
                                @PathVariable String name,
                                @PathVariable String key,
                                @RequestBody Request request,
                                AccessSubject subject) {
        Class<?> entityType;
        String logicalName;
        String normalizedKind;
        if ("documents".equalsIgnoreCase(kind) || "document".equalsIgnoreCase(kind)) {
            DocumentDescriptor descriptor = documents.require(name);
            access.requireWrite(subject, descriptor);
            // Checking an existing record is a write-side read: it must be inside the write scope.
            if (request.id() != null && !documents.inScope(subject, descriptor, request.id(), AccessMode.WRITE)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            }
            entityType = descriptor.javaClass();
            logicalName = descriptor.logicalName();
            normalizedKind = "document";
        } else if ("catalogs".equalsIgnoreCase(kind) || "catalog".equalsIgnoreCase(kind)) {
            CatalogDescriptor descriptor = catalogs.require(name);
            access.requireWrite(subject, descriptor);
            if (request.id() != null && !catalogs.inScope(subject, descriptor, request.id(), AccessMode.WRITE)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            }
            entityType = descriptor.javaClass();
            logicalName = descriptor.logicalName();
            normalizedKind = "catalog";
        } else {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown entity kind " + kind);
        }
        CheckDefinition definition = checks.check(entityType, key);
        if (definition == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Unknown check " + key + " for " + logicalName);
        }
        List<Feedback> feedback = definition.check().check(new CheckContext(
                normalizedKind, logicalName, entityType, request.id(), request.values()));
        return feedback == null ? List.of() : feedback.stream()
                .filter(item -> item != null && !item.message().isBlank())
                .toList();
    }

    public record Request(UUID id, Map<String, Object> values) {
    }
}
