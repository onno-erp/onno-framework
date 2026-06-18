package su.onno.mail.template;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.util.ArrayList;
import java.util.List;

public class MailScanner {

    public List<MailTemplateDescriptor> scan(List<String> basePackages) {
        List<MailTemplateDescriptor> result = new ArrayList<>();

        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(MailTemplate.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(MailTemplates.class));

        for (String pkg : basePackages) {
            for (BeanDefinition bd : scanner.findCandidateComponents(pkg)) {
                Class<?> cls;
                try {
                    cls = Class.forName(bd.getBeanClassName());
                } catch (ClassNotFoundException e) {
                    throw new IllegalStateException("Failed to load class " + bd.getBeanClassName(), e);
                }
                for (MailTemplate t : cls.getAnnotationsByType(MailTemplate.class)) {
                    String tpl = t.template().isBlank() ? "classpath:/mail/" + t.name() + ".html" : t.template();
                    result.add(new MailTemplateDescriptor(cls, t.name(), t.subject(), tpl, t.html(), t.replyTo()));
                }
            }
        }
        return result;
    }
}
