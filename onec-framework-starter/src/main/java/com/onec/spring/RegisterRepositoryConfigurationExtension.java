package com.onec.spring;

import com.onec.repository.RegisterRepository;

import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.core.io.ResourceLoader;
import org.springframework.data.repository.config.RepositoryConfiguration;
import org.springframework.data.repository.config.RepositoryConfigurationExtensionSupport;
import org.springframework.data.repository.config.RepositoryConfigurationSource;

import java.util.Collection;
import java.util.Collections;

public class RegisterRepositoryConfigurationExtension extends RepositoryConfigurationExtensionSupport {

    @Override
    public String getModuleName() {
        return "onec Register";
    }

    @Override
    protected String getModulePrefix() {
        return "register";
    }

    @Override
    public String getRepositoryFactoryBeanClassName() {
        return RegisterRepositoryFactoryBean.class.getName();
    }

    @Override
    protected Collection<Class<?>> getIdentifyingTypes() {
        return Collections.singleton(RegisterRepository.class);
    }

    @Override
    public <T extends RepositoryConfigurationSource> Collection<RepositoryConfiguration<T>> getRepositoryConfigurations(
            T configSource, ResourceLoader loader, boolean strictMatch) {
        // Always use strict matching so we only pick up RegisterRepository subtypes
        return super.getRepositoryConfigurations(configSource, loader, true);
    }
}
