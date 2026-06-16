package com.onec.ui.comments;

import com.onec.ui.CurrentUserResolver;
import com.onec.ui.CurrentUserResolver.CurrentUser;
import com.onec.ui.UiAccessService;

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

    private final CommentService comments;
    private final UiAccessService access;
    private final CurrentUserResolver currentUser;
    private final CommentAuthorAvatars authorAvatars;
    private final CommentProperties properties;

    public CommentController(CommentService comments, UiAccessService access,
                             CurrentUserResolver currentUser, CommentAuthorAvatars authorAvatars,
                             CommentProperties properties) {
        this.comments = comments;
        this.access = access;
        this.currentUser = currentUser;
        this.authorAvatars = authorAvatars;
        this.properties = properties;
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
        return thread.stream()
                .map(c -> toJson(c, me, admin, avatars.get(c.authorId())))
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
        Comment saved = comments.add(kind, name, id, me.recordId(), me.displayName(), body);
        return toJson(saved, me, isAdmin(principal), authorAvatars.avatarFor(saved.authorId()));
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
        return ResponseEntity.noContent().build();
    }

    /** Require read access to the owning entity, mapping the route kind to the access type. */
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

    private static Map<String, Object> toJson(Comment c, CurrentUser me, boolean admin, String avatarUrl) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", c.id().toString());
        out.put("authorName", c.authorName());
        out.put("authorAvatarUrl", avatarUrl);
        out.put("body", c.body());
        out.put("createdAt", c.createdAt());
        out.put("editedAt", c.editedAt());
        out.put("mine", c.authorId() != null && c.authorId().equals(me.recordId()));
        out.put("canDelete", canDelete(c, me, admin));
        return out;
    }
}
