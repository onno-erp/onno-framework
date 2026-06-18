package su.onno.lifecycle;

import su.onno.posting.PostingContext;

public interface Postable {

    void handlePosting(PostingContext context);
}
