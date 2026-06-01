package com.onec.spring;

import com.onec.model.AccumulationRecord;
import com.onec.posting.RegisterPersistence;
import com.onec.repository.RegisterRepositoryImpl;

import org.springframework.data.repository.core.EntityInformation;
import org.springframework.data.repository.core.RepositoryInformation;
import org.springframework.data.repository.core.RepositoryMetadata;
import org.springframework.data.repository.core.support.RepositoryFactorySupport;

import java.util.Map;

public class RegisterRepositoryFactory extends RepositoryFactorySupport {

    private final Map<Class<?>, RegisterPersistence<?>> persistenceMap;
    private final Map<Class<?>, RegisterRepositoryImpl<?>> repositoryImplMap;

    public RegisterRepositoryFactory(Map<Class<?>, RegisterPersistence<?>> persistenceMap,
                                     Map<Class<?>, RegisterRepositoryImpl<?>> repositoryImplMap) {
        this.persistenceMap = persistenceMap;
        this.repositoryImplMap = repositoryImplMap;
    }

    @Override
    public <T, ID> EntityInformation<T, ID> getEntityInformation(Class<T> domainClass) {
        return new RegisterEntityInformation<>(domainClass);
    }

    @Override
    protected Object getTargetRepository(RepositoryInformation metadata) {
        Class<?> domainType = metadata.getDomainType();
        RegisterRepositoryImpl<?> impl = repositoryImplMap.get(domainType);
        if (impl != null) {
            return impl;
        }
        throw new IllegalStateException(
                "No RegisterRepositoryImpl found for " + domainType.getName());
    }

    @Override
    protected Class<?> getRepositoryBaseClass(RepositoryMetadata metadata) {
        return RegisterRepositoryImpl.class;
    }
}
