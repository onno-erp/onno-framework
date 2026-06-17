package com.onec.ui.comments;

import com.onec.events.EntityChangedEvent;
import com.onec.ui.CatalogQueryService;
import com.onec.ui.CurrentUserResolver;
import com.onec.ui.CurrentUserResolver.CurrentUser;
import com.onec.ui.DocumentQueryService;
import com.onec.ui.UiAccessService;
import com.onec.ui.UiViewResolver;

import com.onec.ui.comments.MentionResolver.ResolvedMention;

import org.springframework.context.ApplicationEventPublisher;
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
import java.util.UUID;

/**
 * The discussion-thread endpoint. A thread hangs off any catalog or document detail page and is
 * addressed by the same {@code {kind}/{name}/{id}} triple the UI routes use. Reading and posting
 * are gated on <em>read</em> access to the target entity — if you can open the record you can
 * comment on it (see {@link UiAccessService}); deleting is limited to the author or an {@code ADMIN}.
 * Authorship is stamped from the authenticated principal via {@link CurrentUserResolver}, so the
 * client never asserts who it is.
 */
@RestController
@RequestMapping("/api/comments")
public class CommentController {

    private static final String SUPERUSER_ROLE = "ADMIN";

    /**
     * The {@link EntityChangedEvent#entityType()} stamped on a comment-thread change. It is its own
     * kind (not {@code catalog}/{@code document}) so the live stream carries comment posts/deletes
     * without the list, map, or content-pane surfaces — which only react to the modelled kinds —
     * mistaking it for a row edit and refetching. Only the comments widget listens for it.
     */
    private static final String COMMENT_ENTITY_TYPE = "comment";

    private final CommentService comments;
    private final UiAccessService access;
    private final CurrentUserResolver currentUser;
    private final CommentAuthorAvatars authorAvatars;
    private final CommentProperties properties;
    private final UiViewResolver viewResolver;
    private final CatalogQueryService catalogQuery;
    private final DocumentQueryService documentQuery;
    private final MentionResolver mentions;
    private final ApplicationEventPublisher events;

    public CommentController(CommentService comments, UiAccessService access,
                             CurrentUserResolver currentUser, CommentAuthorAvatars authorAvatars,
                             CommentProperties properties, UiViewResolver viewResolver,
                             CatalogQueryService catalogQuery, DocumentQueryService documentQuery,
                             MentionResolver mentions, ApplicationEventPublisher events) {
        this.comments = comments;
        this.access = access;
        this.currentUser = currentUser;
        this.authorAvatars = authorAvatars;
        this.properties = properties;
        this.viewResolver = viewResolver;
        this.catalogQuery = catalogQuery;
        this.documentQuery = documentQuery;
        this.mentions = mentions;
        this.events = events;
    }

    /** The request body for posting a comment. */
    public record CommentRequest(String body) {}

    @GetMapping("/{kind}/{name}/{id}")
    public List<Map<String, Object>> list(@PathVariable String kind, @PathVariable String name,
                                          @PathVariable UUID id, Principal principal) {
        requireRead(kind, name, principal);
        CurrentUser me = currentUser.resolve(principal);
        boolean admin = isAdmin(principal);
        List<Comment> thread = comments.list(kind, name, id);
        Map<String, String> avatars = authorAvatars.avatarsFor(
                thread.stream().map(Comment::authorId).filter(Objects::nonNull).toList());
        // Resolve every mention across the whole thread in one batch (per the viewer's read access),
        // then hand each comment just the ones in its body — a thread mentioning ten customers costs
        // one query, not ten.
        Map<MentionRef, ResolvedMention> resolved = resolveThreadMentions(thread, principal);
        return thread.stream()
                // authorId is null for authors not linked to an identity record (e.g. the built-in
                // admin user). avatars is an immutable map whose get(null) throws NPE, so guard the
                // key — a null-author comment simply has no avatar.
                .map(c -> toJson(c, me, admin, c.authorId() == null ? null : avatars.get(c.authorId()),
                        mentionsFor(c.body(), resolved)))
                .toList();
    }

    @PostMapping("/{kind}/{name}/{id}")
    public Map<String, Object> add(@PathVariable String kind, @PathVariable String name,
                                   @PathVariable UUID id, @RequestBody CommentRequest request,
                                   Principal principal) {
        requireRead(kind, name, principal);
        String body = request == null ? null : request.body();
        if (body == null || body.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Comment cannot be empty");
        }
        body = body.strip();
        if (body.length() > properties.getMaxLength()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Comment must be at most " + properties.getMaxLength() + " characters");
        }
        CurrentUser me = currentUser.resolve(principal);
        // Strip any mention the author can't read before storing — no smuggling a clickable link to
        // a hidden record (it degrades to the token's plain label text instead).
        if (mentionsEnabled()) {
            body = Mentions.degrade(body, ref -> !mentions.canRead(principal, ref));
        }
        Comment saved = comments.add(kind, name, id, me.recordId(), me.displayName(), body);
        // Live-sync the thread: announce the post on the same EntityChangedEvent stream the SSE
        // bridge fans to browsers, scoped to the target's (name, id) so only this thread's open
        // panels refetch. The insert has already committed (its own JDBI transaction), so a viewer
        // reacting to this event reads the new comment back, not a phantom (issues #28, #29).
        events.publishEvent(new EntityChangedEvent(EntityChangedEvent.CREATED, COMMENT_ENTITY_TYPE, name, id, null));
        Map<MentionRef, ResolvedMention> resolved = resolveThreadMentions(List.of(saved), principal);
        if (mentionsEnabled()) {
            // Announce each surviving (author-readable) mention. No consumers ship with the framework;
            // delivery (in-app, cross-node bus, mail) is purely additive via an @EventListener.
            for (MentionRef ref : Mentions.parse(saved.body())) {
                ResolvedMention r = resolved.get(ref);
                if (r != null && r.readable()) {
                    events.publishEvent(new EntityMentionedEvent(saved, ref, me));
                }
            }
        }
        return toJson(saved, me, isAdmin(principal), authorAvatars.avatarFor(saved.authorId()),
                mentionsFor(saved.body(), resolved));
    }

    @DeleteMapping("/{commentId}")
    public ResponseEntity<Void> delete(@PathVariable UUID commentId, Principal principal) {
        Comment comment = comments.find(commentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!canDelete(comment, currentUser.resolve(principal), isAdmin(principal))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only the author or an administrator can delete this comment");
        }
        comments.softDelete(commentId);
        // Same live-sync signal as a post, so the deleted comment vanishes from other open threads.
        events.publishEvent(new EntityChangedEvent(
                EntityChangedEvent.DELETED, COMMENT_ENTITY_TYPE, comment.entityName(), comment.entityId(), null));
        return ResponseEntity.noContent().build();
    }

    /**
     * Gate a thread request: the kind must be a catalog or document, the caller must have read
     * access to the owning entity, and that entity must have comments enabled (the per-entity,
     * opt-in {@link com.onec.ui.EntityView#comments()} switch). A request for an entity that hasn't
     * opted in is a 404 — the comment surface simply doesn't exist there.
     */
    private void requireRead(String kind, String name, Principal principal) {
        String type = switch (kind) {
            case "catalogs" -> "catalog";
            case "documents" -> "document";
            default -> null;
        };
        if (type == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (!access.canRead(principal, type, name)) {
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
    }

    private boolean isAdmin(Principal principal) {
        return access.roles(principal).contains(SUPERUSER_ROLE);
    }

    /** A comment is deletable by its author (matched on the linked record id) or any administrator. */
    private static boolean canDelete(Comment comment, CurrentUser me, boolean admin) {
        if (admin) {
            return true;
        }
        return comment.authorId() != null && comment.authorId().equals(me.recordId());
    }

    private boolean mentionsEnabled() {
        return properties.getMentions().isEnabled();
    }

    /** Resolve every distinct mention across a set of comments, once, for the viewer. */
    private Map<MentionRef, ResolvedMention> resolveThreadMentions(List<Comment> thread, Principal viewer) {
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

    private static Map<String, Object> toJson(Comment c, CurrentUser me, boolean admin, String avatarUrl,
                                              List<Map<String, Object>> mentions) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", c.id().toString());
        out.put("authorName", c.authorName());
        out.put("authorAvatarUrl", avatarUrl);
        out.put("body", c.body());
        out.put("mentions", mentions);
        out.put("createdAt", c.createdAt());
        out.put("editedAt", c.editedAt());
        out.put("mine", c.authorId() != null && c.authorId().equals(me.recordId()));
        out.put("canDelete", canDelete(c, me, admin));
        return out;
    }
}
