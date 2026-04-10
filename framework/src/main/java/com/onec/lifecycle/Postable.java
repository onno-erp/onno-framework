package com.onec.lifecycle;

/**
 * Marker interface for documents that support posting.
 * The actual posting method should be annotated with @HandlePosting
 * and accept RegisterMovementCollection parameters for each register.
 */
public interface Postable {
}
