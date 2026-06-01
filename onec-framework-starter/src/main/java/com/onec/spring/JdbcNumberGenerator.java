package com.onec.spring;

import com.onec.numbering.NumberGenerator;

import org.jdbi.v3.core.Jdbi;

public class JdbcNumberGenerator implements NumberGenerator {

    private final Jdbi jdbi;

    public JdbcNumberGenerator(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    @Override
    public String nextNumber(String entityName, int length) {
        return nextValue(entityName, length);
    }

    @Override
    public String nextCode(String entityName, int length) {
        return nextValue(entityName, length);
    }

    private String nextValue(String entityName, int length) {
        return jdbi.inTransaction(handle -> {
            var existing = handle.createQuery(
                            "SELECT last_value FROM onec_sequences WHERE entity_name = :name FOR UPDATE")
                    .bind("name", entityName)
                    .mapTo(Long.class)
                    .findOne();

            long next;
            if (existing.isPresent()) {
                next = existing.get() + 1;
                handle.createUpdate("UPDATE onec_sequences SET last_value = :val WHERE entity_name = :name")
                        .bind("val", next)
                        .bind("name", entityName)
                        .execute();
            } else {
                next = 1;
                handle.createUpdate("INSERT INTO onec_sequences (entity_name, last_value) VALUES (:name, :val)")
                        .bind("name", entityName)
                        .bind("val", next)
                        .execute();
            }

            return String.format("%0" + length + "d", next);
        });
    }
}
