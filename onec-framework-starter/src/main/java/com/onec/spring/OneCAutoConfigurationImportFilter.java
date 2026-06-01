package com.onec.spring;

import org.springframework.boot.autoconfigure.AutoConfigurationImportFilter;
import org.springframework.boot.autoconfigure.AutoConfigurationMetadata;

/**
 * Excludes Spring Boot's default JdbcRepositoriesAutoConfiguration since
 * OneCRepositoriesAutoConfiguration handles JDBC repos with proper RegisterRepository exclusion.
 */
public class OneCAutoConfigurationImportFilter implements AutoConfigurationImportFilter {

    private static final String EXCLUDED =
            "org.springframework.boot.autoconfigure.data.jdbc.JdbcRepositoriesAutoConfiguration";

    @Override
    public boolean[] match(String[] autoConfigurationClasses, AutoConfigurationMetadata metadata) {
        boolean[] result = new boolean[autoConfigurationClasses.length];
        for (int i = 0; i < autoConfigurationClasses.length; i++) {
            result[i] = !EXCLUDED.equals(autoConfigurationClasses[i]);
        }
        return result;
    }
}
