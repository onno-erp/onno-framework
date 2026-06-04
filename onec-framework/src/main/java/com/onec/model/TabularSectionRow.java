package com.onec.model;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;

import java.util.UUID;

@Getter
@Setter
@ToString
public abstract class TabularSectionRow {

    @Id
    private UUID id;

    /**
     * 1-based position within the tabular section. This is NOT a mapped column for Spring Data
     * JDBC: a document's {@code @TabularSection List<Row>} round-trips through Spring as an ordered
     * one-to-many whose list index already owns the {@code _line_number} key column, so mapping
     * this field to the same column would emit it twice. The JDBI/generic CRUD path still reads and
     * writes {@code _line_number} directly, and {@code OnecAfterConvertCallback} repopulates this
     * field after a repository load.
     */
    @Transient
    private int lineNumber;
}
