package com.onec.spring;

import com.onec.model.DocumentObject;
import com.onec.posting.PostingEngine;
import com.onec.posting.PostingPreview;
import com.onec.posting.PostingService;

public class TimedPostingService extends PostingService {

    private final PostingEngine engine;
    private final OnecMetrics metrics;

    public TimedPostingService(PostingEngine engine, OnecMetrics metrics) {
        super(engine);
        this.engine = engine;
        this.metrics = metrics;
    }

    @Override
    public void post(DocumentObject document) {
        metrics.time("onec.posting-service.post", 1, () -> engine.post(document));
    }

    @Override
    public PostingPreview preview(DocumentObject document) {
        return metrics.time("onec.posting-service.preview", 1, () -> engine.preview(document));
    }

    @Override
    public void unpost(DocumentObject document) {
        metrics.time("onec.posting-service.unpost", 1, () -> engine.unpost(document));
    }
}
