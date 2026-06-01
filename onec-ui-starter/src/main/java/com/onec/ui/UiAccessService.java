package com.onec.ui;

import com.onec.metadata.AccumulationRegisterDescriptor;
import com.onec.metadata.CatalogDescriptor;
import com.onec.metadata.DocumentDescriptor;
import com.onec.metadata.InformationRegisterDescriptor;
import com.onec.metadata.MetadataRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Method;
import java.security.Principal;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class UiAccessService {

    private final MetadataRegistry registry;

    public UiAccessService(MetadataRegistry registry) {
        this.registry = registry;
    }

    public boolean canRead(Principal principal, CatalogDescriptor descriptor) {
        return hasAnyRole(principal, descriptor.readRoles());
    }

    public boolean canWrite(Principal principal, CatalogDescriptor descriptor) {
        return hasAnyRole(principal, effectiveWriteRoles(descriptor.readRoles(), descriptor.writeRoles()));
    }

    public boolean canRead(Principal principal, DocumentDescriptor descriptor) {
        return hasAnyRole(principal, descriptor.readRoles());
    }

    public boolean canWrite(Principal principal, DocumentDescriptor descriptor) {
        return hasAnyRole(principal, effectiveWriteRoles(descriptor.readRoles(), descriptor.writeRoles()));
    }

    public boolean canRead(Principal principal, AccumulationRegisterDescriptor descriptor) {
        return hasAnyRole(principal, descriptor.readRoles());
    }

    public boolean canWrite(Principal principal, AccumulationRegisterDescriptor descriptor) {
        return hasAnyRole(principal, effectiveWriteRoles(descriptor.readRoles(), descriptor.writeRoles()));
    }

    public boolean canRead(Principal principal, InformationRegisterDescriptor descriptor) {
        return hasAnyRole(principal, descriptor.readRoles());
    }

    public void requireRead(Principal principal, CatalogDescriptor descriptor) {
        if (!canRead(principal, descriptor)) throw forbidden("catalog", descriptor.logicalName());
    }

    public void requireWrite(Principal principal, CatalogDescriptor descriptor) {
        if (!canWrite(principal, descriptor)) throw forbidden("catalog", descriptor.logicalName());
    }

    public void requireRead(Principal principal, DocumentDescriptor descriptor) {
        if (!canRead(principal, descriptor)) throw forbidden("document", descriptor.logicalName());
    }

    public void requireWrite(Principal principal, DocumentDescriptor descriptor) {
        if (!canWrite(principal, descriptor)) throw forbidden("document", descriptor.logicalName());
    }

    public void requireRead(Principal principal, AccumulationRegisterDescriptor descriptor) {
        if (!canRead(principal, descriptor)) throw forbidden("register", descriptor.logicalName());
    }

    public boolean canRead(Principal principal, String type, String name) {
        return switch (type) {
            case "catalog" -> registry.allCatalogs().stream()
                    .filter(d -> d.logicalName().equals(name))
                    .findFirst()
                    .map(d -> canRead(principal, d))
                    .orElse(false);
            case "document" -> registry.allDocuments().stream()
                    .filter(d -> d.logicalName().equals(name))
                    .findFirst()
                    .map(d -> canRead(principal, d))
                    .orElse(false);
            case "register" -> registry.allRegisters().stream()
                    .filter(d -> d.logicalName().equals(name))
                    .findFirst()
                    .map(d -> canRead(principal, d))
                    .orElse(false);
            default -> false;
        };
    }

    public Set<String> roles(Principal principal) {
        if (principal == null) return Set.of();
        Set<String> roles = new LinkedHashSet<>();
        try {
            Method getAuthorities = principal.getClass().getMethod("getAuthorities");
            Object result = getAuthorities.invoke(principal);
            if (result instanceof Collection<?> collection) {
                for (Object authority : collection) {
                    Method getAuthority = authority.getClass().getMethod("getAuthority");
                    Object value = getAuthority.invoke(authority);
                    if (value instanceof String role) {
                        roles.add(normalizeRole(role));
                    }
                }
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return roles;
    }

    private boolean hasAnyRole(Principal principal, List<String> requiredRoles) {
        if (principal == null) return false;
        if (requiredRoles == null || requiredRoles.isEmpty()) return true;
        Set<String> actualRoles = roles(principal);
        return requiredRoles.stream()
                .map(UiAccessService::normalizeRole)
                .anyMatch(actualRoles::contains);
    }

    private List<String> effectiveWriteRoles(List<String> readRoles, List<String> writeRoles) {
        return writeRoles == null || writeRoles.isEmpty() ? readRoles : writeRoles;
    }

    private static String normalizeRole(String role) {
        String normalized = role == null ? "" : role.trim().toUpperCase(Locale.ROOT);
        return normalized.startsWith("ROLE_") ? normalized.substring("ROLE_".length()) : normalized;
    }

    private ResponseStatusException forbidden(String type, String name) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Current user is not allowed to access " + type + ": " + name);
    }
}
