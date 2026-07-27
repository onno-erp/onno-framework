package su.onno.fixtures;

import su.onno.annotations.Attribute;
import su.onno.annotations.Document;
import su.onno.annotations.TabularSection;
import su.onno.lifecycle.Postable;
import su.onno.model.DocumentObject;
import su.onno.posting.PostingContext;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Document(name = "TestSecretDocuments")
@Getter
@Setter
public class TestSecretDocument extends DocumentObject implements Postable {

    @Attribute(secret = true)
    private String apiKey;

    @TabularSection(name = "lines")
    private List<TestSecretLine> lines = new ArrayList<>();

    @Override
    public void handlePosting(PostingContext context) {
        // This fixture only exercises the posting write-back path.
    }
}
