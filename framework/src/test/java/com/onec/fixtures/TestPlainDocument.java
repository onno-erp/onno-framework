package com.onec.fixtures;

import com.onec.annotations.Attribute;
import com.onec.annotations.Document;
import com.onec.model.DocumentObject;

import lombok.Getter;
import lombok.Setter;

@Document(name = "TestPlainDocs")
@Getter
@Setter
public class TestPlainDocument extends DocumentObject {

    @Attribute(length = 100)
    private String memo;
}
