package su.onno.print;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.util.ArrayList;
import java.util.List;

public class PrintScanner {

    public List<PrintTemplateDescriptor> scan(List<String> basePackages) {
        List<PrintTemplateDescriptor> result = new ArrayList<>();

        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(PrintTemplate.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(PrintTemplates.class));

        for (String pkg : basePackages) {
            for (BeanDefinition bd : scanner.findCandidateComponents(pkg)) {
                Class<?> cls;
                try {
                    cls = Class.forName(bd.getBeanClassName());
                } catch (ClassNotFoundException e) {
                    throw new IllegalStateException("Failed to load class " + bd.getBeanClassName(), e);
                }
                for (PrintTemplate t : cls.getAnnotationsByType(PrintTemplate.class)) {
                    result.add(toDescriptor(cls, t));
                }
            }
        }
        return result;
    }

    private PrintTemplateDescriptor toDescriptor(Class<?> target, PrintTemplate t) {
        String tpl = t.template().isBlank() ? "classpath:/print/" + t.name() + ".html" : t.template();
        return new PrintTemplateDescriptor(target, t.name(), t.label(), tpl, t.format(), t.order());
    }
}
