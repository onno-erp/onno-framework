package su.onno.spring.codegen.accessfixtures;

import su.onno.access.Permission;

/** An application's permission holder; only public static final constants are emitted. */
final class GenPermissions {

    public static final Permission EXPORT = Permission.of("gen.export", "Export the ledger");

    static final Permission NOT_PUBLIC = Permission.of("gen.hidden", "Not public");

    public static Permission notFinal = Permission.of("gen.mutable", "Not final");

    private GenPermissions() {
    }
}
