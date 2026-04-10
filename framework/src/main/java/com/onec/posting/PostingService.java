package com.onec.posting;

import com.onec.model.DocumentObject;

public class PostingService {

    private final PostingEngine engine;

    public PostingService(PostingEngine engine) {
        this.engine = engine;
    }

    public void post(DocumentObject document) {
        engine.post(document);
    }

    public void unpost(DocumentObject document) {
        engine.unpost(document);
    }
}
