package com.onec.fixtures;

import com.onec.annotations.Attribute;
import com.onec.annotations.Document;
import com.onec.lifecycle.BeforeWriteHandler;
import com.onec.model.DocumentObject;

import lombok.Getter;
import lombok.Setter;

@Document(name = "TestFailingDocs")
@Getter
@Setter
public class TestFailingDocument extends DocumentObject implements BeforeWriteHandler {

    @Attribute(length = 100)
    private String note;

    @Override
    public void beforeWrite() {
        throw new RuntimeException("Validation failed");
    }
}
