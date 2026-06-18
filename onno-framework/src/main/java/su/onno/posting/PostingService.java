package su.onno.posting;

import su.onno.model.DocumentObject;

public class PostingService {

    private final PostingEngine engine;

    public PostingService(PostingEngine engine) {
        this.engine = engine;
    }

    public void post(DocumentObject document) {
        engine.post(document);
    }

    public PostingPreview preview(DocumentObject document) {
        return engine.preview(document);
    }

    public void unpost(DocumentObject document) {
        engine.unpost(document);
    }
}
