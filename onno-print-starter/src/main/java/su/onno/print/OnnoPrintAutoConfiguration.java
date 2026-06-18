package su.onno.print;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ResourceLoader;
import org.thymeleaf.TemplateEngine;

import java.util.List;

@AutoConfiguration
@ConditionalOnClass(TemplateEngine.class)
@ConditionalOnProperty(prefix = "onno.print", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(PrintProperties.class)
public class OnnoPrintAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public PrintTemplateRegistry printTemplateRegistry(ApplicationContext context,
                                                       PrintProperties properties) {
        PrintTemplateRegistry registry = new PrintTemplateRegistry();

        List<String> packages = properties.getBasePackages().isEmpty()
                ? AutoConfigurationPackages.get(context)
                : properties.getBasePackages();

        new PrintScanner().scan(packages).forEach(registry::register);
        return registry;
    }

    @Bean
    @ConditionalOnMissingBean
    public PrintService printService(PrintTemplateRegistry registry,
                                     ResourceLoader resourceLoader,
                                     PrintProperties properties) {
        return new ThymeleafPrintService(registry, resourceLoader, properties);
    }
}
