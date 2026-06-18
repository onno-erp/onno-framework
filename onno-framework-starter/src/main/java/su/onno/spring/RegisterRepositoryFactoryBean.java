package su.onno.spring;

import su.onno.posting.RegisterPersistence;
import su.onno.repository.RegisterRepositoryImpl;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.core.support.RepositoryFactoryBeanSupport;
import org.springframework.data.repository.core.support.RepositoryFactorySupport;

import java.util.Map;

public class RegisterRepositoryFactoryBean<T extends Repository<S, UUID>, S, UUID>
        extends RepositoryFactoryBeanSupport<T, S, UUID> {

    @Autowired
    private Map<Class<?>, RegisterPersistence<?>> registerPersistenceMap;

    @Autowired
    private Map<Class<?>, RegisterRepositoryImpl<?>> registerRepositoryImplMap;

    public RegisterRepositoryFactoryBean(Class<? extends T> repositoryInterface) {
        super(repositoryInterface);
    }

    @Override
    protected RepositoryFactorySupport createRepositoryFactory() {
        return new RegisterRepositoryFactory(registerPersistenceMap, registerRepositoryImplMap);
    }
}
