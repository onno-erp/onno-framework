package com.onec.spring;

import com.onec.metadata.*;
import com.onec.schema.SchemaGenerator;

import org.jdbi.v3.core.Jdbi;
import org.springframework.beans.factory.InitializingBean;

import javax.sql.DataSource;
import java.util.List;

public class SchemaInitializer implements InitializingBean {

    private final DataSource dataSource;
    private final List<String> scanPackages;

    public SchemaInitializer(DataSource dataSource, List<String> scanPackages) {
        this.dataSource = dataSource;
        this.scanPackages = scanPackages;
    }

    @Override
    public void afterPropertiesSet() {
        MetadataRegistry registry = new MetadataRegistry();
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());

        CatalogScanner catalogScanner = new CatalogScanner();
        for (Class<?> clazz : catalogScanner.scan(scanPackages)) {
            CatalogDescriptor descriptor = scanner.scan(clazz);
            registry.register(descriptor);
        }

        DocumentScanner documentScanner = new DocumentScanner();
        for (Class<?> clazz : documentScanner.scan(scanPackages)) {
            DocumentDescriptor descriptor = scanner.scanDocument(clazz);
            registry.registerDocument(descriptor);
        }

        AccumulationScanner accumulationScanner = new AccumulationScanner();
        for (Class<?> clazz : accumulationScanner.scan(scanPackages)) {
            registry.registerAccumulation(scanner.scanRegister(clazz));
        }

        SchemaGenerator schemaGenerator = new SchemaGenerator(registry, new DefaultTypeMapping());
        schemaGenerator.execute(Jdbi.create(dataSource));
    }
}
