package su.onno.spring;

import su.onno.types.Ref;
import su.onno.types.RefResolver;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.core.ResolvableType;
import org.springframework.data.repository.CrudRepository;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class SpringRefResolver implements RefResolver, SmartInitializingSingleton {

    private final ApplicationContext applicationContext;
    private final Map<Class<?>, CrudRepository<?, UUID>> repositoryMap = new HashMap<>();

    public SpringRefResolver(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public void afterSingletonsInstantiated() {
        String[] beanNames = applicationContext.getBeanNamesForType(
                ResolvableType.forClass(CrudRepository.class));

        for (String beanName : beanNames) {
            ResolvableType repoType = ResolvableType.forClass(
                    applicationContext.getType(beanName));
            ResolvableType[] interfaces = repoType.getInterfaces();
            for (ResolvableType iface : interfaces) {
                ResolvableType[] generics = iface.getGenerics();
                if (generics.length >= 1) {
                    Class<?> domainType = generics[0].resolve();
                    if (domainType != null) {
                        @SuppressWarnings("unchecked")
                        CrudRepository<?, UUID> repo = (CrudRepository<?, UUID>)
                                applicationContext.getBean(beanName);
                        repositoryMap.put(domainType, repo);
                        break;
                    }
                }
            }
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Optional<T> resolve(Ref<T> ref) {
        CrudRepository<?, UUID> repo = repositoryMap.get(ref.type());
        if (repo == null) {
            throw new IllegalArgumentException(
                    "No repository found for type " + ref.type().getName());
        }
        return (Optional<T>) repo.findById(ref.id());
    }
}
