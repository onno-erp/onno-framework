package com.onec.spring;

import com.onec.metadata.*;
import com.onec.migration.AppMigration;
import com.onec.migration.MigrationRunner;
import com.onec.schema.SchemaMode;
import com.onec.schema.SchemaUpgrader;

import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

import javax.sql.DataSource;
import java.util.List;

public class SchemaInitializer implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(SchemaInitializer.class);

    private final DataSource dataSource;
    private final List<String> scanPackages;
    private final SchemaMode mode;
    private final boolean allowDestructive;
    private final List<AppMigration> migrations;

    public SchemaInitializer(DataSource dataSource, List<String> scanPackages) {
        this(dataSource, scanPackages, SchemaMode.APPLY, false, List.of());
    }

    public SchemaInitializer(DataSource dataSource, List<String> scanPackages,
                             SchemaMode mode, boolean allowDestructive,
                             List<AppMigration> migrations) {
        this.dataSource = dataSource;
        this.scanPackages = scanPackages;
        this.mode = mode;
        this.allowDestructive = allowDestructive;
        this.migrations = migrations;
    }

    @Override
    public void afterPropertiesSet() {
        MetadataRegistry registry = new MetadataRegistry();
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());

        CatalogScanner catalogScanner = new CatalogScanner();
        for (Class<?> clazz : catalogScanner.scan(scanPackages)) {
            CatalogDescriptor descriptor = scanner.scan(clazz);
            registry.registerCatalog(descriptor);
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

        for (Class<?> clazz : new EnumerationScanner().scan(scanPackages)) {
            registry.registerEnumeration(scanner.scanEnumeration(clazz));
        }
        for (Class<?> clazz : new InformationRegisterScanner().scan(scanPackages)) {
            registry.registerInformationRegister(scanner.scanInformationRegister(clazz));
        }
        for (Class<?> clazz : new ConstantScanner().scan(scanPackages)) {
            registry.registerConstant(scanner.scanConstant(clazz));
        }

        if (mode == SchemaMode.OFF) {
            log.info("onec.schema.mode=off — skipping schema upgrade and migrations.");
            return;
        }

        Jdbi jdbi = Jdbi.create(dataSource);
        new SchemaUpgrader(registry, mode, allowDestructive).run(jdbi);

        MigrationRunner runner = new MigrationRunner(registry);
        switch (mode) {
            case APPLY -> runner.run(jdbi, migrations);
            case PLAN -> {
                List<AppMigration> pending = runner.pending(jdbi, migrations);
                if (!pending.isEmpty()) {
                    log.info("Pending migrations (not applied in plan mode): {}",
                            pending.stream().map(AppMigration::version).toList());
                }
            }
            case VALIDATE -> {
                List<AppMigration> pending = runner.pending(jdbi, migrations);
                if (!pending.isEmpty()) {
                    throw new IllegalStateException(
                            "Schema validation failed (onec.schema.mode=validate): unapplied migrations "
                                    + pending.stream().map(AppMigration::version).toList());
                }
            }
            default -> { }
        }
    }
}
