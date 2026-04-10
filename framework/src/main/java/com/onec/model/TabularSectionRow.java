package com.onec.model;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import org.springframework.data.annotation.Id;

import java.util.UUID;

@Getter
@Setter
@ToString
public abstract class TabularSectionRow {

    @Id
    private UUID id;
    private int lineNumber;
}
