package su.onno.fixtures;

import su.onno.annotations.Attribute;
import su.onno.annotations.Document;
import su.onno.model.DocumentObject;

import lombok.Getter;
import lombok.Setter;

@Document(name = "TestPlainDocs")
@Getter
@Setter
public class TestPlainDocument extends DocumentObject {

    @Attribute(length = 100)
    private String memo;
}
