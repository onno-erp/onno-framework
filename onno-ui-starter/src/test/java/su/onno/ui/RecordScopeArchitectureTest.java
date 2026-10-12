package su.onno.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Architecture guard for record policies (design §7): entity tables are only queried through the
 * scoped query/command services and the few audited classes below. Any other class in the UI or MCP
 * starters that reads an entity table with JDBI, or injects a typed repository, bypasses record
 * scopes — add it to the allowlist only after making it apply {@link RecordScopeCompiler} (or
 * documenting why it serves trusted/system data).
 */
class RecordScopeArchitectureTest {

    /** Classes allowed to query entity tables directly, each with the reason it is safe. */
    private static final Set<String> AUDITED = Set.of(
            // the scoped read/write services themselves
            "CatalogQueryService.java", "DocumentQueryService.java", "RegisterQueryService.java",
            "InformationRegisterQueryService.java", "EntityQuerySupport.java",
            "CatalogCommandService.java", "DocumentCommandService.java", "RecordWriteGuard.java",
            // scope-aware helpers
            "RefResolver.java", "RecordAccess.java", "UiEventPublisher.java", "MentionResolver.java",
            // DDL only
            "RecordScopeIndexes.java",
            // identity lookups of the caller's own record (login → identity row), never listed
            "CurrentUserResolver.java", "CommentAuthorAvatars.java",
            // reads one record id from a committed change event as trusted code; the recipient's
            // feed is filtered by record scope when it is read (NotificationController)
            "AssignmentNotificationSource.java");

    @Test
    void onlyAuditedClassesQueryEntityTables() throws IOException {
        Set<String> offenders = new TreeSet<>();
        for (Path root : List.of(Path.of("src/main/java"), Path.of("../onno-mcp-starter/src/main/java"))) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                    String source = Files.readString(file);
                    boolean queriesEntityTables = source.contains("tableName()")
                            && (source.contains("Jdbi") || source.contains("JdbcTemplate"));
                    boolean injectsRepositories = source.contains("CatalogRepository<")
                            || source.contains("DocumentRepository<")
                            || source.contains("RegisterRepository<");
                    if ((queriesEntityTables || injectsRepositories)
                            && !AUDITED.contains(file.getFileName().toString())) {
                        offenders.add(root.relativize(file).toString());
                    }
                }
            }
        }
        assertThat(offenders)
                .as("classes reading entity tables outside the record-scoped services")
                .isEmpty();
    }
}
