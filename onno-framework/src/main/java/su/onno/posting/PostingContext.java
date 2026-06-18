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

    @SuppressWarnings("unchecked")
    public <T extends AccumulationRecord> RegisterRepository<T> movements(Class<T> registerClass) {
        RegisterRepositoryImpl<?> repo = repositoryMap.get(registerClass);
        if (repo == null) {
            throw new IllegalArgumentException(
                    "No RegisterRepository found for " + registerClass.getName());
        }
        touched.put(registerClass, repo);
        return (RegisterRepository<T>) repo;
    }

    public Collection<RegisterRepositoryImpl<?>> touchedRepositories() {
        return touched.values();
    }
}
