package su.onno.fixtures;

import su.onno.annotations.Attribute;
import su.onno.annotations.Document;
import su.onno.lifecycle.BeforeWriteHandler;
import su.onno.model.DocumentObject;

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
