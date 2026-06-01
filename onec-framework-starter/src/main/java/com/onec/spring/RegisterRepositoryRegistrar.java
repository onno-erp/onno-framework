package com.onec.spring;

import com.onec.repository.RegisterRepository;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.type.filter.AssignableTypeFilter;

import java.util.List;

/**
 * Scans for RegisterRepository interfaces and ensures they are registered
 * with RegisterRepositoryFactoryBean, replacing any JDBC-created definitions.
 * Implements PriorityOrdered to run after Spring Data JDBC scanning.
 */
public class RegisterRepositoryRegistrar implements BeanDefinitionRegistryPostProcessor, PriorityOrdered {

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
        if (!(registry instanceof BeanFactory beanFactory)) {
            return;
        }

        if (!AutoConfigurationPackages.has(beanFactory)) {
            return;
        }

        List<String> packages = AutoConfigurationPackages.get(beanFactory);

        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(RegisterRepository.class));

        for (String pkg : packages) {
            for (var bd : scanner.findCandidateComponents(pkg)) {
                String className = bd.getBeanClassName();
                if (className == null) continue;

                try {
                    Class<?> repoClass = Class.forName(className);
                    if (!repoClass.isInterface()) continue;
                    if (repoClass == RegisterRepository.class) continue;

                    String simpleName = repoClass.getSimpleName();
                    String beanName = Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1);

                    // Remove any existing JDBC definition and replace with ours
                    if (registry.containsBeanDefinition(beanName)) {
                        registry.removeBeanDefinition(beanName);
                    }

                    RootBeanDefinition rbd = new RootBeanDefinition(RegisterRepositoryFactoryBean.class);
                    rbd.getConstructorArgumentValues().addGenericArgumentValue(repoClass);
                    rbd.setAttribute("factoryBeanObjectType", repoClass);
                    registry.registerBeanDefinition(beanName, rbd);

                } catch (ClassNotFoundException ex) {
                    throw new RuntimeException("Failed to load register repository: " + className, ex);
                }
            }
        }
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
    }
}
