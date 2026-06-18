package su.onno.spring;

import org.springframework.boot.autoconfigure.data.AbstractRepositoryConfigurationSourceSupport;
import org.springframework.data.repository.config.RepositoryConfigurationExtension;

import java.lang.annotation.Annotation;

public class RegisterRepositoriesAutoConfiguration extends AbstractRepositoryConfigurationSourceSupport {

    @Override
    protected Class<? extends Annotation> getAnnotation() {
        return EnableRegisterRepositories.class;
    }

    @Override
    protected Class<?> getConfiguration() {
        return EnableRegisterRepositoriesConfiguration.class;
    }

    @Override
    protected RepositoryConfigurationExtension getRepositoryConfigurationExtension() {
        return new RegisterRepositoryConfigurationExtension();
    }

    @EnableRegisterRepositories
    private static class EnableRegisterRepositoriesConfiguration {
    }
}
