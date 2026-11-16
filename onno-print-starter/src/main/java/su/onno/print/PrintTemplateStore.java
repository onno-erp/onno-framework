package su.onno.print;

import org.jdbi.v3.core.Jdbi;

import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * Admin-uploaded replacement templates, one per print form, kept in the framework-managed
 * {@code onno_print_templates} table. A stored template takes precedence over the form's built-in
 * template until it is removed, so templates can be edited without a redeploy and every node of a
 * cluster sees the same file.
 */
public class PrintTemplateStore {

    /** A stored template; {@code scope} is {@code catalogs/{name}} or {@code documents/{name}}. */
    public record StoredTemplate(String scope, String formKey, String fileName, byte[] content,
                                 String uploadedBy, Instant uploadedAt) {
    }

    private final Jdbi jdbi;

    public PrintTemplateStore(Jdbi jdbi) {
        this.jdbi = jdbi;
        jdbi.useHandle(h -> {
            String product;
            try {
                product = h.getConnection().getMetaData().getDatabaseProductName();
            } catch (SQLException e) {
                throw new IllegalStateException("Could not read the database product name", e);
            }
            String binary = product != null && product.toLowerCase().contains("postgres") ? "BYTEA" : "BLOB";
            h.execute("CREATE TABLE IF NOT EXISTS onno_print_templates (scope VARCHAR(300) NOT NULL, "
                    + "form_key VARCHAR(100) NOT NULL, file_name VARCHAR(255) NOT NULL, content " + binary
                    + " NOT NULL, uploaded_by VARCHAR(255), uploaded_at TIMESTAMP NOT NULL, "
                    + "PRIMARY KEY (scope, form_key))");
        });
    }

    public Optional<StoredTemplate> find(String scope, String formKey) {
        return jdbi.withHandle(h -> h.createQuery("SELECT scope, form_key, file_name, content, uploaded_by, uploaded_at "
                        + "FROM onno_print_templates WHERE scope = :scope AND form_key = :key")
                .bind("scope", scope).bind("key", formKey)
                .map((rs, ctx) -> new StoredTemplate(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getBytes(4), rs.getString(5), rs.getTimestamp(6).toInstant()))
                .findOne());
    }

    /** Metadata of every stored template (content omitted). */
    public List<StoredTemplate> list() {
        return jdbi.withHandle(h -> h.createQuery("SELECT scope, form_key, file_name, uploaded_by, uploaded_at "
                        + "FROM onno_print_templates")
                .map((rs, ctx) -> new StoredTemplate(rs.getString(1), rs.getString(2), rs.getString(3),
                        null, rs.getString(4), rs.getTimestamp(5).toInstant()))
                .list());
    }

    public void save(String scope, String formKey, String fileName, byte[] content, String uploadedBy) {
        java.sql.Timestamp now = java.sql.Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MILLIS));
        jdbi.useTransaction(h -> {
            h.createUpdate("DELETE FROM onno_print_templates WHERE scope = :scope AND form_key = :key")
                    .bind("scope", scope).bind("key", formKey).execute();
            h.createUpdate("INSERT INTO onno_print_templates (scope, form_key, file_name, content, uploaded_by, uploaded_at) "
                            + "VALUES (:scope, :key, :file, :content, :by, :at)")
                    .bind("scope", scope).bind("key", formKey).bind("file", fileName)
                    .bind("content", content).bind("by", uploadedBy).bind("at", now)
                    .execute();
        });
    }

    /** Removes the stored template; true when one existed. */
    public boolean delete(String scope, String formKey) {
        return jdbi.withHandle(h -> h.createUpdate("DELETE FROM onno_print_templates WHERE scope = :scope AND form_key = :key")
                .bind("scope", scope).bind("key", formKey).execute()) > 0;
    }
}
