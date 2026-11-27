package su.onno.posting;

import su.onno.model.DocumentObject;

/**
 * Posting operations on documents saved and committed through the ordinary write pipeline.
 * These operations change register movements and posting state, not document attributes or
 * tabular sections, and do not invoke {@code BeforeWriteHandler.beforeWrite()}.
 */
public class PostingService {

    private final PostingEngine engine;

    public PostingService(PostingEngine engine) {
        this.engine = engine;
    }

    public void post(DocumentObject document) {
        engine.post(document);
    }

    public void repost(DocumentObject document) {
        engine.repost(document);
    }

    public PostingPreview preview(DocumentObject document) {
        return engine.preview(document);
    }

    public void unpost(DocumentObject document) {
        engine.unpost(document);
    }
}
