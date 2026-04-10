package com.onec.lifecycle;

import com.onec.posting.PostingContext;

public interface Postable {

    void handlePosting(PostingContext context);
}
