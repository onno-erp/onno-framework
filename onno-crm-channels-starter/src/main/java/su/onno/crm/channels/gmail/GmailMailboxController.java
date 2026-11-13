package su.onno.crm.channels.gmail;

import java.security.Principal;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import su.onno.crm.repository.ConversationRepository;
import su.onno.crm.service.CrmInboxWorkspaceService;

/**
 * Find mail in connected Gmail mailboxes, archived included, and bring a thread back into the CRM.
 *
 * <p>Every route needs inbox workspace access (restoring needs write access) and only reaches the
 * mailboxes {@link GmailBridge#readableMailboxes} allows: shared ones and the principal's own
 * personal ones. Any other address, including another person's personal mailbox, is a 404.
 */
@RestController
@RequestMapping("/api/crm/gmail")
@ConditionalOnProperty(name="onno.crm.channels.gmail.enabled",havingValue="true")
public class GmailMailboxController {
    private static final Logger log = LoggerFactory.getLogger(GmailMailboxController.class);
    static final int MAX_QUERY = 500;
    private static final Pattern MESSAGE_ID = Pattern.compile("[A-Za-z0-9_-]{1,100}");
    private static final Pattern PAGE_TOKEN = Pattern.compile("[A-Za-z0-9_.~-]{1,512}");

    private final GmailBridge bridge;
    private final CrmInboxWorkspaceService workspaces;
    private final ConversationRepository conversations;

    public GmailMailboxController(GmailBridge bridge, CrmInboxWorkspaceService workspaces, ConversationRepository conversations) {
        this.bridge = bridge;
        this.workspaces = workspaces;
        this.conversations = conversations;
    }

    /** {@code canModify}: the grant lets the CRM mark mail read and move it to the Inbox in Gmail. */
    public record Mailbox(String address, String label, boolean canModify) {}
    /** One Gmail message; {@code date} is ISO-8601, {@code conversationId} null unless the thread is in the CRM and readable. */
    public record Hit(String id, String threadId, String from, String subject, String snippet, String date,
                      boolean archived, String conversationId) {}
    public record SearchResult(List<Hit> messages, String nextPageToken) {}
    public record RestoreRequest(String account, String messageId, Boolean toInbox) {}
    public record Restored(String conversationId, boolean movedToInbox) {}

    @GetMapping("/accounts")
    public List<Mailbox> accounts(Principal principal) {
        workspaces.requireAccess(principal, false);
        return bridge.readableMailboxes(principal).stream()
                .map(a -> new Mailbox(a.email(), bridge.mailboxLabel(a), bridge.canModify(a))).toList();
    }

    @GetMapping("/search")
    public SearchResult search(@RequestParam(required = false) String account, @RequestParam(required = false) String q,
                               @RequestParam(required = false) String pageToken, Principal principal) {
        workspaces.requireAccess(principal, false);
        String query = q == null ? "" : q.strip();
        if (query.isEmpty() || query.length() > MAX_QUERY || query.chars().anyMatch(c -> c < 32))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a search of 1–" + MAX_QUERY + " characters");
        if (pageToken != null && !pageToken.isBlank() && !PAGE_TOKEN.matcher(pageToken).matches())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid page token");
        var mailbox = mailbox(account, principal);
        var page = gmail(mailbox, () -> bridge.search(mailbox, query, pageToken));
        var hits = page.messages().stream().map(hit -> new Hit(hit.id(), hit.threadId(), hit.from(), hit.subject(),
                hit.snippet(), hit.date(), hit.archived(), readable(hit.conversationId(), principal))).toList();
        return new SearchResult(hits, page.nextPageToken());
    }

    @PostMapping("/restore")
    public Restored restore(@RequestBody(required = false) RestoreRequest request, Principal principal) {
        workspaces.requireAccess(principal, true);
        if (request == null || request.messageId() == null || !MESSAGE_ID.matcher(request.messageId()).matches())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a Gmail message to restore");
        var mailbox = mailbox(request.account(), principal);
        boolean toInbox = Boolean.TRUE.equals(request.toInbox());
        var restored = gmail(mailbox, () -> bridge.restore(mailbox, request.messageId(), toInbox));
        return new Restored(restored.conversationId().toString(), restored.movedToInbox());
    }

    /** A mailbox this principal may use; anything else reads as absent. */
    private GmailBridge.Account mailbox(String address, Principal principal) {
        if (address == null || address.isBlank() || address.length() > 320)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Mailbox not found");
        return bridge.readableMailbox(address, principal)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Mailbox not found"));
    }

    /** The conversation id when this principal may read that conversation; null otherwise. */
    private String readable(UUID conversationId, Principal principal) {
        if (conversationId == null) return null;
        return conversations.findActiveById(conversationId)
                .filter(c -> workspaces.canAccess(c, principal, false))
                .map(c -> c.getId().toString()).orElse(null);
    }

    private interface GmailCall<T> { T call() throws Exception; }

    /** Gmail failures as plain answers; the log keeps status and reason, never mail content. */
    private <T> T gmail(GmailBridge.Account mailbox, GmailCall<T> call) {
        try {
            return call.call();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (GmailClient.ApiFailure e) {
            log.warn("Gmail mailbox request for {} failed ({})", mailbox.email(), e.describe());
            if (e.rateLimited()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Gmail asked us to slow down; try again in a few minutes");
            if (e.status == 404) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Gmail could not find that message");
            if (e.status == 401 || e.status == 403) throw new ResponseStatusException(HttpStatus.CONFLICT, "Reconnect this mailbox in Connections");
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Gmail did not answer; try again");
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        } catch (IllegalArgumentException e) {
            // GmailClient's "Reconnect Gmail to restore access": the stored grant no longer works.
            log.warn("Gmail mailbox request for {} failed ({})", mailbox.email(), e.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Reconnect this mailbox in Connections");
        } catch (Exception e) {
            log.warn("Gmail mailbox request for {} failed ({})", mailbox.email(), e.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Gmail did not answer; try again");
        }
    }
}
