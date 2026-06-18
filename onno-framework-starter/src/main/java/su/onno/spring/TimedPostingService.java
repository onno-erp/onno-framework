package su.onno.spring;

import su.onno.model.DocumentObject;
import su.onno.posting.PostingEngine;
import su.onno.posting.PostingPreview;
import su.onno.posting.PostingService;

public class TimedPostingService extends PostingService {

    private final PostingEngine engine;
    private final OnnoMetrics metrics;

    public TimedPostingService(PostingEngine engine, OnnoMetrics metrics) {
        super(engine);
        this.engine = engine;
        this.metrics = metrics;
    }

    @Override
    public void post(DocumentObject document) {
        metrics.time("onno.posting-service.post", 1, () -> engine.post(document));
    }

    @Override
    public PostingPreview preview(DocumentObject document) {
        return metrics.time("onno.posting-service.preview", 1, () -> engine.preview(document));
    }

    @Override
    public void unpost(DocumentObject document) {
        metrics.time("onno.posting-service.unpost", 1, () -> engine.unpost(document));
    }
}
