package su.onno.posting;

import su.onno.model.AccumulationRecord;
import su.onno.repository.RegisterRepository;
import su.onno.repository.RegisterRepositoryImpl;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

public class PostingContext {

    private final Map<Class<?>, RegisterRepositoryImpl<?>> repositoryMap;
    private final Map<Class<?>, RegisterRepositoryImpl<?>> touched = new LinkedHashMap<>();

    public PostingContext(Map<Class<?>, RegisterRepositoryImpl<?>> repositoryMap) {
        this.repositoryMap = repositoryMap;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public <T extends AccumulationRecord> RegisterRepository<T> movements(Class<T> registerClass) {
        RegisterRepositoryImpl<?> shared = repositoryMap.get(registerClass);
        if (shared == null) {
            throw new IllegalArgumentException(
                    "No RegisterRepository found for " + registerClass.getName());
        }
        // Hand back a repo scoped to *this* post, not the shared singleton. Movements buffer in
        // RegisterRepositoryImpl.pendingMovements; that list is process-wide on the singleton, so
        // concurrent posts would append to / iterate / clear the same list at once — yielding
        // ConcurrentModificationException, duplicate-PK inserts, and movements leaking between
        // unrelated documents. The scoped repo wraps the same stateless RegisterPersistence (so
        // reads still go through the one shared JDBI) but owns its own pending-movement buffer.
        RegisterRepositoryImpl<?> scoped = touched.computeIfAbsent(registerClass,
                k -> new RegisterRepositoryImpl(shared.getPersistence(), k));
        return (RegisterRepository<T>) scoped;
    }

    public Collection<RegisterRepositoryImpl<?>> touchedRepositories() {
        return touched.values();
    }
}
