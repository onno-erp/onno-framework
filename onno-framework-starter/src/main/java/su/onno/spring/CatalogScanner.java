package su.onno.spring;

import su.onno.annotations.Catalog;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;

import java.util.ArrayList;
import java.util.List;

public class CatalogScanner {

    public List<Class<?>> scan(List<String> basePackages) {
        List<Class<?>> result = new ArrayList<>();

        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Catalog.class));

        for (String pkg : basePackages) {
            for (BeanDefinition bd : scanner.findCandidateComponents(pkg)) {
                try {
                    // Resolve against the thread-context classloader, not this class's own: under a
                    // devtools restart the app's classes live in the restart classloader while this
                    // scanner sits in a jar on the base one — Class.forName here would return a
                    // twin Class the identity-keyed MetadataRegistry can never match.
                    result.add(ClassUtils.forName(bd.getBeanClassName(), null));
                } catch (ClassNotFoundException e) {
                    throw new RuntimeException("Failed to load catalog class: " + bd.getBeanClassName(), e);
                }
            }
        }

        return result;
    }
}
