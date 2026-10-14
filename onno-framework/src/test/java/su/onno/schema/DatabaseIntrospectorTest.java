package su.onno.schema;

import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseIntrospectorTest {

    @Test
    void readsOnlyTablesAndColumnsFromTheCurrentSchema() {
        Jdbi jdbi = Jdbi.create("jdbc:h2:mem:introspector;DB_CLOSE_DELAY=-1");
        jdbi.useHandle(handle -> {
            handle.execute("CREATE TABLE shared_table (own_column VARCHAR(50))");
            handle.execute("CREATE SCHEMA shadow");
            handle.execute("CREATE TABLE shadow.shared_table (foreign_column VARCHAR(50))");
            handle.execute("CREATE TABLE shadow.foreign_only (id UUID PRIMARY KEY)");
        });

        DatabaseIntrospector.DbState state = jdbi.withHandle(DatabaseIntrospector::read);

        assertThat(state.columns("shared_table")).containsExactly("OWN_COLUMN");
        assertThat(state.hasTable("foreign_only")).isFalse();
    }

    @Test
    void readsForeignKeyDependenciesInTheCurrentSchema() {
        Jdbi jdbi = Jdbi.create("jdbc:h2:mem:introspector_fk;DB_CLOSE_DELAY=-1");
        jdbi.useHandle(handle -> {
            handle.execute("CREATE TABLE parent_table (id UUID PRIMARY KEY)");
            handle.execute("""
                    CREATE TABLE child_table (
                        id UUID PRIMARY KEY,
                        parent_id UUID REFERENCES parent_table(id)
                    )
                    """);
            handle.execute("CREATE SCHEMA shadow");
            handle.execute("CREATE TABLE shadow.parent_table (id UUID PRIMARY KEY)");
            handle.execute("""
                    CREATE TABLE shadow.child_table (
                        id UUID PRIMARY KEY,
                        parent_id UUID REFERENCES shadow.parent_table(id)
                    )
                    """);
        });

        DatabaseIntrospector.DbState state = jdbi.withHandle(DatabaseIntrospector::read);

        assertThat(state.referencedTables("child_table")).containsExactly("PARENT_TABLE");
        assertThat(state.referencedTables("parent_table")).isEmpty();
    }
}
