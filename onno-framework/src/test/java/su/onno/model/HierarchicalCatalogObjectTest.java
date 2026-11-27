package su.onno.model;

import su.onno.annotations.Catalog;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.MetadataScanner;
import su.onno.types.Ref;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class HierarchicalCatalogObjectTest {
    @Catalog(name = "TypedFolders", hierarchical = true)
    static class Folder extends HierarchicalCatalogObject<Folder> { }

    @Catalog(name = "MissingBase", hierarchical = true)
    static class MissingBase extends CatalogObject { }

    @Catalog(name = "MissingFlag")
    static class MissingFlag extends HierarchicalCatalogObject<MissingFlag> { }

    @Catalog(name = "WrongSelf", hierarchical = true)
    static class WrongSelf extends HierarchicalCatalogObject<Folder> { }

    @SuppressWarnings("rawtypes")
    @Catalog(name = "RawSelf", hierarchical = true)
    static class RawSelf extends HierarchicalCatalogObject { }

    abstract static class Intermediate<E extends HierarchicalCatalogObject<E>> extends HierarchicalCatalogObject<E> { }

    @Catalog(name = "Indirect", hierarchical = true)
    static class Indirect extends Intermediate<Indirect> { }

    private final MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());

    @Test void parentIsTypedAndCanBeCleared() {
        Folder folder = new Folder();
        Ref<Folder> parent = Ref.of(Folder.class, UUID.randomUUID());
        folder.setParent(parent);
        assertThat(folder.getParent()).isEqualTo(parent);
        folder.setParent(null);
        assertThat(folder.getParent()).isNull();
        assertThat(scanner.scan(Folder.class).hierarchical()).isTrue();
        assertThat(scanner.scan(Indirect.class).hierarchical()).isTrue();
    }

    @Test void mismatchedFlagAndBaseFailDuringMetadataScanning() {
        assertThatIllegalArgumentException().isThrownBy(() -> scanner.scan(MissingBase.class))
                .withMessageContaining("HierarchicalCatalogObject<Self>");
        assertThatIllegalArgumentException().isThrownBy(() -> scanner.scan(MissingFlag.class))
                .withMessageContaining("hierarchical = false");
        assertThatIllegalArgumentException().isThrownBy(() -> scanner.scan(WrongSelf.class))
                .withMessageContaining("own entity type");
        assertThatIllegalArgumentException().isThrownBy(() -> scanner.scan(RawSelf.class))
                .withMessageContaining("own entity type");
    }

    @Test void anotherCatalogCannotBePassedToTheTypedSetter(@TempDir Path directory) throws Exception {
        Path source = directory.resolve("ParentTypes.java");
        String declarations = """
                import su.onno.model.HierarchicalCatalogObject;
                import su.onno.types.Ref;
                import java.util.UUID;
                class ParentTypes {
                    static class Category extends HierarchicalCatalogObject<Category> { }
                    static class Customer extends HierarchicalCatalogObject<Customer> { }
                    void assign(Category category) {
                """;
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).isNotNull();
        String classpath = System.getProperty("java.class.path");
        Files.writeString(source, declarations + "category.setParent(Ref.of(Category.class, UUID.randomUUID())); }}");
        assertThat(compiler.run(null, null, null, "-proc:none", "-classpath", classpath,
                "-d", directory.toString(), source.toString())).isZero();
        Files.writeString(source, declarations + "category.setParent(Ref.of(Customer.class, UUID.randomUUID())); }}");
        var diagnostics = new java.io.ByteArrayOutputStream();
        assertThat(compiler.run(null, null, diagnostics, "-proc:none", "-classpath", classpath,
                "-d", directory.toString(), source.toString())).isNotZero();
        assertThat(diagnostics.toString()).contains("incompatible");
    }
}
