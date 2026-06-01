package com.onec.spring;

import com.onec.annotations.Constant;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.util.ArrayList;
import java.util.List;

public class ConstantScanner {

    public List<Class<?>> scan(List<String> basePackages) {
        List<Class<?>> result = new ArrayList<>();

        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Constant.class));

        for (String pkg : basePackages) {
            for (BeanDefinition bd : scanner.findCandidateComponents(pkg)) {
                try {
                    result.add(Class.forName(bd.getBeanClassName()));
                } catch (ClassNotFoundException e) {
                    throw new RuntimeException("Failed to load constant class: " + bd.getBeanClassName(), e);
                }
            }
        }

        return result;
    }
}
