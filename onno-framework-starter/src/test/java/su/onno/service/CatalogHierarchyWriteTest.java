package su.onno.service;

import su.onno.access.AccessSubject;
import su.onno.annotations.Catalog;
import su.onno.lifecycle.BeforeWriteHandler;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;
import su.onno.model.CatalogObject;
import su.onno.schema.SchemaGenerator;
import su.onno.spring.OnnoSimpleJdbcRepository;
import su.onno.validation.ValidationException;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.annotation.Transient;
import org.springframework.data.jdbc.core.JdbcAggregateOperations;
import org.springframework.data.jdbc.core.convert.JdbcConverter;
import org.springframework.data.jdbc.core.mapping.JdbcMappingContext;
import org.springframework.data.mapping.PersistentEntity;

import javax.sql.DataSource;
import java.sql.SQLException;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real Spring Data JDBC saves, not a mocked parent lookup. */
class CatalogHierarchyWriteTest {

    @Catalog(name = "HierarchyNodes", hierarchical = true)
    public static class Node extends CatalogObject implements BeforeWriteHandler {
        @Transient Runnable beforeValidation;

        @Override public void beforeWrite() {
            if (beforeValidation != null) beforeValidation.run();
        }
    }

    @Catalog(name = "HierarchyFlat")
    public static class Flat extends CatalogObject { }

    private TestEntityStore.Stack stack;
    private CatalogDescriptor descriptor;
    private CatalogCommandService commands;
    private OnnoSimpleJdbcRepository<Node, UUID> repository;

    @BeforeEach
    @SuppressWarnings("unchecked") // The persistent entity is looked up with exactly Node.class.
    void setUp() {
        var registry = new MetadataRegistry();
        var scanner = new MetadataScanner(new DefaultNamingStrategy());
        descriptor = scanner.scan(Node.class);
        registry.registerCatalog(descriptor);
        registry.registerCatalog(scanner.scan(Flat.class));
        stack = TestEntityStore.builder(dataSource(), registry).build();
        new SchemaGenerator(registry).execute(stack.jdbi());
        commands = new CatalogCommandService(registry, stack.jdbi(), new WebProperties(),
                new CatalogQueryService(registry, stack.jdbi()), new AccessService(registry), stack.store());
        repository = new OnnoSimpleJdbcRepository<>(stack.context().getBean(JdbcAggregateOperations.class),
                (PersistentEntity<Node, ?>) stack.context().getBean(JdbcMappingContext.class)
                        .getRequiredPersistentEntity(Node.class), stack.context().getBean(JdbcConverter.class));
    }

    @AfterEach void close() { stack.close(); }

    protected DataSource dataSource() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=3000");
        return source;
    }

    @Test void rootItemsAndFoldersAndMovingToAnotherFolderAreAllowed() {
        Node first = save(node(true, null));
        Node second = save(node(true, null));
        Node item = save(node(false, first.getId()));
        Node folder = save(node(true, first.getId()));
        item.setParent(second.getId());
        save(item);
        folder.setParent(second.getId());
        save(folder);
        assertThat(repository.findById(item.getId()).orElseThrow().getParent()).isEqualTo(second.getId());
        assertThat(repository.findById(folder.getId()).orElseThrow().getParent()).isEqualTo(second.getId());
    }

    @Test void unknownAndForeignCatalogParentsAreRejected() {
        invalid(node(false, UUID.randomUUID()), "existing folder");
        Flat flat = new Flat();
        flat.setFolder(true);
        stack.store().inTransaction(() -> stack.store().save(flat));
        invalid(node(false, flat.getId()), "existing folder");
    }

    @Test void ordinaryItemAndDeletedFolderCannotBeParents() {
        Node item = save(node(false, null));
        invalid(node(false, item.getId()), "must be a folder");
        Node folder = save(node(true, null));
        folder.setDeletionMark(true);
        save(folder);
        invalid(node(true, folder.getId()), "not marked for deletion");
    }

    @Test void newAndExistingRecordsCannotBeTheirOwnParents() {
        Node fresh = node(true, null);
        fresh.setId(UUID.randomUUID());
        fresh.setParent(fresh.getId());
        invalid(fresh, "cycle");
        Node stored = save(node(true, null));
        stored.setParent(stored.getId());
        invalid(stored, "cycle");
        assertThat(repository.findById(stored.getId()).orElseThrow().getParent()).isNull();
    }

    @Test void indirectCycleDoesNotChangeTheStoredParentOrVersion() {
        Node root = save(node(true, null));
        Node child = save(node(true, root.getId()));
        Node grandchild = save(node(true, child.getId()));
        Integer version = root.getVersion();
        root.setParent(grandchild.getId());
        invalid(root, "cycle");
        Node unchanged = repository.findById(root.getId()).orElseThrow();
        assertThat(unchanged.getParent()).isNull();
        assertThat(unchanged.getVersion()).isEqualTo(version);
    }

    @Test void beforeWriteCannotBypassTheParentCheck() {
        Node item = node(false, null);
        item.beforeValidation = () -> item.setParent(UUID.randomUUID());
        invalid(item, "existing folder");
        assertThat(repository.count()).isZero();
    }

    @Test void aCorruptAncestorCycleIsRejectedAndCanBeRepairedByMovingToTheRoot() {
        Node first = save(node(true, null));
        Node second = save(node(true, first.getId()));
        rawParent(first, second.getId()); // Legacy corruption, outside the supported write path.
        invalid(node(false, second.getId()), "cycle");
        first.setParent(null);
        save(first);
        save(node(false, second.getId()));
    }

    @Test void brokenAncestorLinksAndNonFolderAncestorsAreRejected() {
        Node folder = save(node(true, null));
        rawParent(folder, UUID.randomUUID());
        invalid(node(false, folder.getId()), "existing folder");
        Node item = save(node(false, null));
        rawParent(folder, item.getId());
        invalid(node(false, folder.getId()), "must be a folder");
    }

    @Test void aNonEmptyFolderCannotLoseItsFolderOrActiveFlag() {
        Node folder = save(node(true, null));
        save(node(false, folder.getId()));
        folder.setFolder(false);
        invalid(folder, "active children");
        folder.setFolder(true);
        folder.setDeletionMark(true);
        invalid(folder, "active children");
    }

    @Test void deletingTheChildrenAllowsDeletingTheirFolder() {
        Node folder = save(node(true, null));
        Node item = save(node(false, folder.getId()));
        item.setDeletionMark(true);
        save(item);
        folder.setDeletionMark(true);
        save(folder);
        assertThat(repository.findById(folder.getId()).orElseThrow().isDeletionMark()).isTrue();
    }

    @Test void repositoryDeleteCannotRemoveANonEmptyFolder() {
        Node folder = save(node(true, null));
        save(node(false, folder.getId()));
        assertThatThrownBy(() -> stack.store().inTransaction(() -> repository.delete(folder)))
                .isInstanceOfSatisfying(ValidationException.class,
                        error -> assertThat(error.getField()).isEqualTo("parent"));
        assertThat(repository.findById(folder.getId()).orElseThrow().isDeletionMark()).isFalse();
    }

    @Test void flatCatalogsKeepTheirExistingWriteBehavior() {
        Flat item = new Flat();
        item.setParent(UUID.randomUUID());
        stack.store().inTransaction(() -> stack.store().save(item));
        assertThat(stack.store().find(Flat.class, item.getId()).getParent()).isEqualTo(item.getParent());
    }

    @Test void longValidAncestorChainsAreAcceptedWithoutRecursion() {
        UUID parent = null;
        for (int depth = 0; depth < 128; depth++) {
            UUID id = UUID.randomUUID();
            UUID previous = parent;
            String code = "D" + depth;
            stack.jdbi().useHandle(h -> h.createUpdate("INSERT INTO " + descriptor.tableName()
                            + " (_id, _code, _is_folder, _deletion_mark, _parent, _version)"
                            + " VALUES (:id, :code, true, false, CAST(:parent AS UUID), 0)")
                    .bind("id", id).bind("code", code).bindByType("parent", previous, UUID.class).execute());
            parent = id;
        }
        assertThat(save(node(false, parent)).getParent()).isEqualTo(parent);
    }

    @Test void liveValidationUsesTheSameCheckWithoutWritingAndSupportsClearingTheParent() {
        Node root = save(node(true, null));
        Node child = save(node(true, root.getId()));
        var report = commands.validate(descriptor, root.getId(), Map.of("parent", child.getId()), AccessSubject.system());
        assertThat(report).containsEntry("valid", false);
        assertThat(((Map<?, ?>) report.get("fieldErrors")).containsKey("parent")).isTrue();
        assertThat(repository.findById(root.getId()).orElseThrow().getParent()).isNull();
        var clear = new HashMap<String, Object>();
        clear.put("parent", null);
        assertThat(commands.validate(descriptor, child.getId(), clear, AccessSubject.system()))
                .containsEntry("valid", true);
        commands.update(descriptor, child.getId(), clear, AccessSubject.system());
        assertThat(repository.findById(child.getId()).orElseThrow().getParent()).isNull();
    }

    @Test void malformedParentIsReportedOnTheParentField() {
        assertThatThrownBy(() -> commands.create(descriptor, Map.of("parent", "not-a-uuid"), AccessSubject.system()))
                .isInstanceOfSatisfying(ValidationException.class,
                        error -> assertThat(error.getField()).isEqualTo("parent"));
    }

    @Test void simultaneousMovesCannotCommitACycle() throws Exception {
        Node first = save(node(true, null));
        Node second = save(node(true, null));
        var ready = new CyclicBarrier(2);
        first.setParent(second.getId());
        second.setParent(first.getId());
        first.beforeValidation = second.beforeValidation = () -> {
            try { ready.await(5, TimeUnit.SECONDS); }
            catch (Exception error) { throw new IllegalStateException(error); }
        };
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> trySave(first));
            var b = executor.submit(() -> trySave(second));
            assertThat(java.util.List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        Node a = repository.findById(first.getId()).orElseThrow();
        Node b = repository.findById(second.getId()).orElseThrow();
        assertThat(a.getParent() == null || b.getParent() == null).isTrue();
    }

    private boolean trySave(Node node) {
        try { save(node); return true; }
        catch (RuntimeException rejected) {
            if (rejected instanceof ValidationException) {
                assertThat(rejected).hasMessageContaining("cycle");
            } else {
                Throwable cause = rejected;
                while (!(cause instanceof SQLException) && cause.getCause() != null) cause = cause.getCause();
                assertThat(cause).isInstanceOfSatisfying(SQLException.class,
                        sql -> assertThat(sql.getSQLState()).isIn("40001", "40P01"));
            }
            return false;
        }
    }

    private Node save(Node node) {
        return stack.store().inTransaction(() -> repository.save(node));
    }

    private void invalid(Node node, String message) {
        assertThatThrownBy(() -> save(node)).isInstanceOfSatisfying(ValidationException.class, error -> {
            assertThat(error.getField()).isEqualTo("parent");
            assertThat(error.fieldErrors()).containsKey("parent");
            assertThat(error.getMessage()).contains(message);
        });
    }

    private void rawParent(Node node, UUID parent) {
        stack.jdbi().useHandle(h -> h.createUpdate("UPDATE " + descriptor.tableName() + " SET _parent = :parent WHERE _id = :id")
                .bind("parent", parent).bind("id", node.getId()).execute());
    }

    private static Node node(boolean folder, UUID parent) {
        Node node = new Node();
        node.setFolder(folder);
        node.setParent(parent);
        return node;
    }
}
