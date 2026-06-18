package su.onno.metadata;

import su.onno.fixtures.TestSecretAccount;
import su.onno.schema.SchemaGenerator;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class SecretAttributeMetadataTest {

    private final MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());

    @Test
    void scanner_carriesSecretFlag() {
        CatalogDescriptor desc = scanner.scan(TestSecretAccount.class);

        AttributeDescriptor password = attr(desc, "wsPassword");
        AttributeDescriptor username = attr(desc, "username");

        assertThat(password.secret()).isTrue();
        assertThat(username.secret()).isFalse();
    }

    @Test
    void schema_secretStringColumnIsText_notVarchar() {
        MetadataRegistry registry = new MetadataRegistry();
        registry.registerCatalog(scanner.scan(TestSecretAccount.class));
        String ddl = new SchemaGenerator(registry).generateDDL().stream()
                .filter(s -> s.contains("catalog_test_secret_accounts"))
                .findFirst().orElseThrow();

        // Secret value is stored encrypted (base64, larger than the declared length), so the
        // column widens to TEXT while the ordinary String attribute stays VARCHAR.
        assertThat(ddl).contains("ws_password TEXT");
        assertThat(ddl).contains("username VARCHAR(100)");
    }

    private static AttributeDescriptor attr(CatalogDescriptor desc, String fieldName) {
        return desc.attributes().stream()
                .filter(a -> a.fieldName().equals(fieldName))
                .findFirst().orElseThrow();
    }
}
