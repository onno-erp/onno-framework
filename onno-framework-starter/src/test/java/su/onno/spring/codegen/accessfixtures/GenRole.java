package su.onno.spring.codegen.accessfixtures;

import su.onno.access.RoleKey;

/** Roles for the codegen test; {@code Auditor} is emitted normalized. */
public enum GenRole implements RoleKey {
    SALES, MANAGER, Auditor
}
