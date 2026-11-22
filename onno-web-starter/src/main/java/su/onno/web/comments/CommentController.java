package su.onno.web.comments;

import su.onno.access.AccessMode;
import su.onno.access.AccessSubject;
import su.onno.access.CorePermissions;
import su.onno.service.CatalogQueryService;
import su.onno.service.CurrentUserResolver;
import su.onno.service.CurrentUserResolver.CurrentUser;
import su.onno.service.DocumentQueryService;
import su.onno.service.AccessService;
import su.onno.web.DefaultListViews;

import su.onno.web.comments.MentionResolver.ResolvedMention;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The discussion-thread endpoint. A thread hangs off any catalog or document detail page and is
 * addressed by the same {@code {kind}/{name}/{id}} triple the UI routes use. Reading and posting
 * are gated on <em>read</em> access to the target — the entity grant (see {@link AccessService})
 * and, for a record-scoped caller, the record itself (404 outside the scope): if you can open the
 * record you can comment on it. Deleting is limited to the author or a holder of
 * {@link CorePermissions#MODERATE_COMMENTS} ({@code comments.moderate}).
 * Authorship is stamped from the authenticated principal via {@link CurrentUserResolver}, so the
 * client never asserts who it is.
 */
@RestController
@RequestMapping("/api/comments")
public class CommentController {

    private static final Set<String> ALLOWED_REACTIONS = Set.of("👍", "❤️", "🎉", "👀", "✅");

    private final CommentService comments;
    private final AccessService access;
    private final CurrentUserResolver currentUser;
    private final CommentAuthorAvatars authorAvatars;
    private final CommentProperties properties;
    private final DefaultListViews viewResolver;
    private final CatalogQueryService catalogQuery;
    private final DocumentQueryService documentQuery;
    private final MentionResolver mentions;

    public CommentController(CommentService comments, AccessService access,
                             CurrentUserResolver currentUser, CommentAuthorAvatars authorAvatars,
                             CommentProperties properties, DefaultListViews viewResolver,
                             CatalogQueryService catalogQuery, DocumentQueryService documentQuery,
                             MentionResolver mentions) {
        this.comments = comments;
        this.access = access;
        this.currentUser = currentUser;
        this.authorAvatars = authorAvatars;
        this.properties = properties;
        this.viewResolver = viewResolver;
        this.catalogQuery = catalogQuery;
        this.documentQuery = documentQuery;
        this.mentions = mentions;
    }

    /** The request body for posting a comment. */
    public record CommentRequest(String body, UUID parentId) {}

    /** The request body for toggling a reaction. */
    public record ReactionRequest(String emoji) {}

    @GetMapping("/{kind}/{name}/{id}")
    public Map<String, Object> list(@PathVariable String kind, @PathVariable String name,
                                          @PathVariable UUID id, Principal principal, AccessSubject subject,
                                          @org.springframework.web.bind.annotation.RequestParam(required = false) String cursor,
                                          @org.springframework.web.bind.annotation.RequestParam(defaultValue = "100") int limit) {
        requireRead(kind, name, id, subject);
        CurrentUser me = currentUser.resolve(principal);
        boolean moderator = isModerator(principal);
        var page = comments.page(kind, name, id, cursor, limit,
                su.onno.query.Cursor.context(su.onno.query.Cursor.subjectContext(subject),
                        catalogQuery.scopes().policies().fingerprint(subject)));
        List<Comment> thread = page.rows();
        Map<String, String> avatars = authorAvatars.avatarsFor(
                thread.stream().map(Comment::authorId).filter(Objects::nonNull).toList());
        // Resolve every mention across the whole thread in one batch (per the viewer's read access),
        // then hand each comment just the ones in its body — a thread mentioning ten customers costs
        // one query, not ten.
        Map<MentionRef, ResolvedMention> resolved = resolveThreadMentions(thread, subject);
        Map<UUID, List<CommentService.CommentReaction>> reactions = comments.reactionsFor(
                thread.stream().map(Comment::id).toList(), reactionUserKey(me));
        var rows = thread.stream()
                // authorId is null for authors not linked to an identity record (e.g. the built-in
                // admin user). avatars is an immutable map whose get(null) throws NPE, so guard the
                // key — a null-author comment simply has no avatar.
                .map(c -> toJson(c, me, moderator, c.authorId() == null ? null : avatars.get(c.authorId()),
                        mentionsFor(c.body(), resolved), reactions.getOrDefault(c.id(), List.of())))
                .toList();
        return su.onno.service.KeysetWindow.envelope(new su.onno.query.KeysetWindow.Page<>(rows,
                page.nextCursor(), page.hasMore()));
    }

    @PostMapping("/{kind}/{name}/{id}")
    public Map<String, Object> add(@PathVariable String kind, @PathVariable String name,
                                   @PathVariable UUID id, @RequestBody CommentRequest request,
                                   Principal principal, AccessSubject subject) {
        requireRead(kind, name, id, subject);
        String body = request == null ? null : request.body();
        UUID parentId = request == null ? null : request.parentId();
        CurrentUser me = currentUser.resolve(principal);
        Comment saved;
        try {
            saved = comments.add(kind, name, id, me, body, parentId, subject);
        } catch (CommentService.InvalidCommentException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage(), e);
        }
        Map<MentionRef, ResolvedMention> resolved = resolveThreadMentions(List.of(saved), subject);
        return toJson(saved, me, isModerator(principal), authorAvatars.avatarFor(saved.authorId()),
                mentionsFor(saved.body(), resolved), List.of());
    }

    @PostMapping("/{commentId}/reactions")
    public List<Map<String, Object>> toggleReaction(@PathVariable UUID commentId,
                                                    @RequestBody ReactionRequest request,
                                                    Principal principal, AccessSubject subject) {
        Comment comment = comments.find(commentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        requireRead(comment.entityType(), comment.entityName(), comment.entityId(), subject);
        String emoji = request == null ? null : request.emoji();
        if (!ALLOWED_REACTIONS.contains(emoji)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Unsupported reaction");
        }
        CurrentUser me = currentUser.resolve(principal);
        comments.toggleReaction(comment, reactionUserKey(me), emoji);
        return reactionsJson(comments.reactionsFor(List.of(commentId), reactionUserKey(me))
                .getOrDefault(commentId, List.of()));
    }

    @DeleteMapping("/{commentId}")
    public ResponseEntity<Void> delete(@PathVariable UUID commentId, Principal principal, AccessSubject subject) {
        Comment comment = comments.find(commentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        requireRead(comment.entityType(), comment.entityName(), comment.entityId(), subject);
        if (!canDelete(comment, currentUser.resolve(principal), isModerator(principal))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only the author or a comment moderator (comments.moderate) can delete this comment");
        }
        comments.softDelete(comment);
        return ResponseEntity.noContent().build();
    }

    /**
     * Gate a thread request: the kind must be a catalog or document, the caller must have read
     * access to the owning entity, and that entity must have comments enabled (the per-entity,
     * opt-in {@link su.onno.web.EntityView#comments()} switch). A request for an entity that hasn't
     * opted in is a 404 — the comment surface simply doesn't exist there. For a record-scoped caller
     * the record must also be inside its read scope (404 otherwise, like a missing record).
     */
    private void requireRead(String kind, String name, UUID id, AccessSubject subject) {
        String type = switch (kind) {
            case "catalogs" -> "catalog";
            case "documents" -> "document";
            default -> null;
        };
        if (type == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (!access.canRead(subject, type, name)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Current user is not allowed to read " + type + ": " + name);
        }
        Class<?> entity = "catalogs".equals(kind)
                ? catalogQuery.require(name).javaClass()
                : documentQuery.require(name).javaClass();
        if (!viewResolver.commentsEnabled(entity)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Comments are not enabled for " + type + ": " + name);
        }
        if (id != null && catalogQuery.scopes().isScoped(entity, subject, AccessMode.READ)) {
            boolean readable = "catalogs".equals(kind)
                    ? catalogQuery.inScope(subject, catalogQuery.require(name), id, AccessMode.READ)
                    : documentQuery.inScope(subject, documentQuery.require(name), id, AccessMode.READ);
            if (!readable) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            }
        }
    }

    private boolean isModerator(Principal principal) {
        return access.has(principal, CorePermissions.MODERATE_COMMENTS);
    }

    /** A comment is deletable by its author (matched on the linked record id) or a moderator. */
    private static boolean canDelete(Comment comment, CurrentUser me, boolean moderator) {
        if (moderator) {
            return true;
        }
        return comment.authorId() != null && comment.authorId().equals(me.recordId());
    }

    private boolean mentionsEnabled() {
        return properties.getMentions().isEnabled();
    }

    /** Resolve every distinct mention across a set of comments, once, for the viewer. */
    private Map<MentionRef, ResolvedMention> resolveThreadMentions(List<Comment> thread, AccessSubject viewer) {
        if (!mentionsEnabled()) {
            return Map.of();
        }
        List<MentionRef> all = new ArrayList<>();
        for (Comment c : thread) {
            all.addAll(Mentions.parse(c.body()));
        }
        Map<MentionRef, ResolvedMention> index = new LinkedHashMap<>();
        for (ResolvedMention r : mentions.resolve(all, viewer)) {
            index.put(new MentionRef(r.kind(), r.name(), r.id()), r);
        }
        return index;
    }

    /** The resolved mentions present in one body, in first-seen order, as response JSON. */
    private List<Map<String, Object>> mentionsFor(String body, Map<MentionRef, ResolvedMention> resolved) {
        if (resolved.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (MentionRef ref : Mentions.parse(body)) {
            ResolvedMention r = resolved.get(ref);
            if (r != null) {
                out.add(r.toJson());
            }
        }
        return out;
    }

    private static Map<String, Object> toJson(Comment c, CurrentUser me, boolean moderator, String avatarUrl,
                                              List<Map<String, Object>> mentions,
                                              List<CommentService.CommentReaction> reactions) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", c.id().toString());
        out.put("authorName", c.authorName());
        out.put("authorAvatarUrl", avatarUrl);
        out.put("body", c.body());
        out.put("parentId", c.parentId() == null ? null : c.parentId().toString());
        out.put("mentions", mentions);
        out.put("reactions", reactionsJson(reactions));
        out.put("createdAt", c.createdAt());
        out.put("editedAt", c.editedAt());
        out.put("mine", c.authorId() != null && c.authorId().equals(me.recordId()));
        out.put("canDelete", canDelete(c, me, moderator));
        return out;
    }

    private static List<Map<String, Object>> reactionsJson(List<CommentService.CommentReaction> reactions) {
        return reactions.stream().map(r -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("emoji", r.emoji());
            out.put("count", r.count());
            out.put("mine", r.mine());
            return out;
        }).toList();
    }

    private static String reactionUserKey(CurrentUser user) {
        if (user.recordId() != null && !user.recordId().isBlank()) {
            return "record:" + user.recordId();
        }
        if (user.username() != null && !user.username().isBlank()) {
            return "principal:" + user.username();
        }
        return "guest";
    }
}
