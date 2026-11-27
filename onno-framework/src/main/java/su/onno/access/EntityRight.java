package su.onno.access;

/**
 * What a role may do with one entity. Each right implies the ones before it: whoever may post or
 * unpost a document may write it, and whoever may write an entity may read it.
 */
public enum EntityRight {
    /** See the entity's records (lists, record pages, reports, references, exports). */
    READ,
    /** Create, change and delete-mark records. Implies {@link #READ}. */
    WRITE,
    /** Documents: post and re-post. Implies {@link #WRITE}. */
    POST,
    /** Documents: unpost. Implies {@link #WRITE}. */
    UNPOST;

    /** Whether holding this right also grants {@code other}. */
    public boolean implies(EntityRight other) {
        if (this == other) return true;
        return switch (this) {
            case READ -> false;
            case WRITE -> other == READ;
            case POST, UNPOST -> other == READ || other == WRITE;
        };
    }
}
