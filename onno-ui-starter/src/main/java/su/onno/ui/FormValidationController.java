package su.onno.ui;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.DocumentDescriptor;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Supported endpoint for dependency-aware, advisory generated-form validation. */
@RestController
@RequestMapping("/api/form-validation")
public final class FormValidationController {
    private final CatalogQueryService catalogs;
    private final DocumentQueryService documents;
    private final UiAccessService access;
    private final FieldHintResolver definitions;
    private final FormValidationService validations;

    public FormValidationController(CatalogQueryService catalogs,
                                    DocumentQueryService documents,
                                    UiAccessService access,
                                    FieldHintResolver definitions,
                                    FormValidationService validations) {
        this.catalogs = catalogs;
        this.documents = documents;
        this.access = access;
        this.definitions = definitions;
        this.validations = validations;
    }

    @PostMapping("/{kind}/{name}/{key}")
    public List<FormFeedback> validate(@PathVariable String kind,
                                       @PathVariable String name,
                                       @PathVariable String key,
                                       @RequestBody Request request,
                                       Principal principal) {
        Class<?> entityType;
        String logicalName;
        String normalizedKind;
        if ("documents".equalsIgnoreCase(kind) || "document".equalsIgnoreCase(kind)) {
            DocumentDescriptor descriptor = documents.require(name);
            access.requireWrite(principal, descriptor);
            entityType = descriptor.javaClass();
            logicalName = descriptor.logicalName();
            normalizedKind = "document";
        } else if ("catalogs".equalsIgnoreCase(kind) || "catalog".equalsIgnoreCase(kind)) {
            CatalogDescriptor descriptor = catalogs.require(name);
            access.requireWrite(principal, descriptor);
            entityType = descriptor.javaClass();
            logicalName = descriptor.logicalName();
            normalizedKind = "catalog";
        } else {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown entity kind " + kind);
        }
        FormValidation definition = definitions.validation(entityType, key);
        if (definition == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Unknown form validation " + key + " for " + logicalName);
        }
        return validations.validate(definition, new FormValidationContext(
                normalizedKind, logicalName, entityType, request.id(), request.values()));
    }

    public record Request(UUID id, Map<String, Object> values) {
    }
}
