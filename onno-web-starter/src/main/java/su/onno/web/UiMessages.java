package su.onno.web;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The framework's own <em>chrome</em> strings — action buttons, confirmation dialogs, the login
 * screen, empty/loading states, and client-side validation messages — as a single label map with
 * sensible English {@link #DEFAULTS defaults} a deployment can override per key.
 *
 * <p>The chrome lives in two layers (the server-built view descriptors and the React app), so the
 * same resolved map feeds both: the server reads it directly to label the descriptors it emits
 * (see {@link UiViewController}), and {@code GET /api/config} hands the whole map
 * ({@link #asMap()}) to the web client, which indexes into it by the same keys. This is the chrome counterpart to the domain
 * localization an app already has (entity {@code title}, {@code @Attribute displayName}, list-column
 * labels): those cover <em>data/field</em> text, this covers the surrounding shell.
 *
 * <p>Overrides come from {@code onno.ui.messages} (see {@link UiProperties#getMessages()}). Scope is
 * one language per deployment — there is no per-request locale negotiation; an app sets the chrome
 * language once and both layers pick it up. A {@code {name}}-style placeholder in a template is
 * filled by {@link #format(String, Object...)}.
 */
public final class UiMessages {

    /**
     * The English defaults for every chrome key. Both layers index into this same key namespace;
     * the React app ships a mirror of these as its offline fallback (see {@code lib/messages.ts}),
     * so keep the two in sync when adding or changing a key.
     */
    public static final Map<String, String> DEFAULTS;

    static {
        Map<String, String> d = new LinkedHashMap<>();

        // CRM inbox widget (onno-crm-starter) — keep in sync with DEFAULT_MESSAGES.
        d.put("crm.activity.addSummary", "Add a call or meeting summary");
        d.put("crm.activity.callCompleted", "Phone call");
        d.put("crm.activity.callPlanned", "Upcoming call");
        d.put("crm.activity.internal", "Internal · visible to your team");
        d.put("crm.activity.linkError", "Enter an HTTPS recording link.");
        d.put("crm.activity.log", "Log activity");
        d.put("crm.activity.logged", "Logged");
        d.put("crm.activity.meetingPlanned", "Scheduled meeting");
        d.put("crm.activity.openRecording", "Open recording");
        d.put("crm.activity.other", "Meeting summary");
        d.put("crm.activity.planned", "Planned");
        d.put("crm.activity.recordingLink", "Recording link (optional)");
        d.put("crm.activity.save", "Save activity");
        d.put("crm.activity.saving", "Saving…");
        d.put("crm.activity.showLess", "Show less");
        d.put("crm.activity.showMore", "Read full summary");
        d.put("crm.activity.summary", "Summary");
        d.put("crm.activity.summaryPlaceholder", "What was discussed and what happens next");
        d.put("crm.activity.tooLong", "Keep the summary and recording link under 7000 characters.");
        d.put("crm.activity.type", "Activity type");
        d.put("crm.activity.visibility", "Visible to your team in this client's history.");
        d.put("crm.attachments.disabled", "File uploads are turned off for this deployment (onno.media.enabled).");
        d.put("crm.channel.instagram.noFiles", "Instagram fetches files from a public URL, which this deployment does not publish. Send the file by email or WhatsApp.");
        d.put("crm.contact.identities", "Linked identities");
        d.put("crm.contact.noIdentities", "No channel identities linked yet");
        d.put("crm.contact.openConversation", "Open conversation");
        d.put("crm.group.cancel", "Cancel");
        d.put("crm.group.createAndMove", "Create and move");
        d.put("crm.group.delete", "Delete group");
        d.put("crm.group.deleteHint", "Chats stay in your inbox.");
        d.put("crm.group.name", "Group name");
        d.put("crm.group.new", "New group…");
        d.put("crm.group.options", "Group options");
        d.put("crm.group.remove", "Remove from group");
        d.put("crm.group.save", "Save name");
        d.put("crm.group.settings", "Group settings");
        d.put("crm.inbox.list", "Conversation list");
        d.put("crm.inbox.loadMoreError", "Could not load more conversations.");
        d.put("crm.inbox.loadMoreRetry", "Retry loading conversations");
        d.put("crm.note.noMatches", "No matches");
        d.put("crm.note.placeholder", "Leave an internal note… @ mention · # reference");
        d.put("crm.note.references", "Note references");
        d.put("crm.note.referencesError", "Could not load references");
        d.put("crm.note.write", "Write an internal note");
        d.put("crm.connections.removeDraft", "Remove unconnected account");
        d.put("crm.connections.shared", "Team accounts");
        d.put("crm.connections.personal", "My accounts");
        d.put("crm.connections.refresh", "Refresh");
        d.put("crm.connections.sharedTitle", "Connected for the whole team");
        d.put("crm.connections.personalTitle", "Your accounts, your conversations");
        d.put("crm.connections.sharedDescription", "Shared accounts bring customer messages into your team’s inbox. Administrators manage these connections.");
        d.put("crm.connections.personalDescription", "Connect an account for your own work. Only you can manage it and open its conversations. Contact records follow your app’s existing permissions.");
        d.put("crm.connections.accountName", "Account name");
        d.put("crm.connections.save", "Save");
        d.put("crm.connections.cancel", "Cancel");
        d.put("crm.connections.rename", "Rename");
        d.put("crm.connections.ownerOnly", "Only you");
        d.put("crm.connections.sharedAccount", "Shared with the team");
        d.put("crm.connections.adminManaged", "Managed by an administrator.");
        d.put("crm.connections.loadError", "Could not load connections. Try again.");
        d.put("crm.connections.retry", "Retry");
        d.put("crm.connections.loading", "Loading connections");
        d.put("crm.connections.yourAccounts", "Accounts");
        d.put("crm.connections.emptyTitle", "No accounts here yet");
        d.put("crm.connections.emptyDescription", "Choose a provider below to add your first account.");
        d.put("crm.connections.readOnlyDescription", "Your administrator can set up shared accounts for the team.");
        d.put("crm.connections.addProvider", "Add {provider}");
        d.put("crm.connections.namePlaceholder", "For example, Sales inbox");
        d.put("crm.connections.authorizeNext", "Add the account, then authorize it with your provider. Adding a name does not connect or import messages.");
        d.put("crm.connections.saving", "Adding…");
        d.put("crm.connections.addAccount", "Add account");
        d.put("crm.connections.availableProviders", "Available providers");
        d.put("crm.connections.providersDescription", "Connect a ready provider, or see what is needed to enable one.");
        d.put("crm.connections.ready", "Ready to connect");
        d.put("crm.connections.sharedOnly", "Shared accounts only");
        d.put("crm.connections.setupRequired", "Setup required");
        d.put("crm.connections.unavailable", "Not available");
        d.put("crm.connections.details", "Setup details");
        d.put("crm.connections.personalHelp", "Authorize your own mailbox. Its imported conversations stay private to you; workspace permissions still apply.");
        d.put("crm.connections.sharedHelp", "An administrator authorizes a team mailbox. Conversations follow the app’s inbox workspace permissions.");
        d.put("crm.connections.manageAbove", "Manage the existing account above.");
        d.put("crm.connections.noProviders", "No providers match this page’s configuration.");
        d.put("crm.connections.state.CONNECTED", "Connected");
        d.put("crm.connections.state.DISCONNECTED", "Not connected");
        d.put("crm.connections.state.PAUSED", "Paused");
        d.put("crm.connections.state.ERROR", "Needs attention");
        d.put("crm.connections.state.UNAVAILABLE", "Unavailable");
        d.put("crm.connections.disconnect", "Disconnect");
        d.put("crm.connections.disconnectPrompt", "Disconnect this account? Synchronization and sending will stop. Existing conversations remain available.");
        d.put("crm.connections.confirmDisconnect", "Disconnect account");
        d.put("crm.connections.connect", "Connect account");
        d.put("crm.connections.check", "Check connection");
        d.put("crm.connections.pause", "Pause");
        d.put("crm.connections.resume", "Resume");
        d.put("crm.connections.reconnect", "Reconnect");
        d.put("crm.connections.reconnectBot", "Reconnect bot");
        d.put("crm.connections.telegramOpenStart", "Open Telegram and press Start");
        d.put("crm.connections.telegramBusinessSteps", "Then choose {account} in Telegram Settings → Chat Automation (or Telegram Business → Chatbots), select which chats it can access (prefer selected chats over all chats), and allow replies. This connection link expires in 10 minutes.");
        d.put("crm.connections.refreshConnection", "Refresh connection");
        d.put("crm.connections.botToken", "Bot token");
        d.put("crm.connections.telegramBotToken", "Telegram bot token");
        d.put("crm.connections.botTokenHint", "Use a token for {account}. The token is verified and stored on the server; existing conversations stay connected to this bot.");
        d.put("crm.connections.verifyReconnect", "Verify and reconnect");
        d.put("crm.connections.telegramConnectHint", "Open the link in Telegram and press Start to finish connecting.");
        d.put("crm.connections.working", "Working…");
        d.put("crm.connections.actionSuccess", "Account updated.");
        d.put("crm.connections.googleConnected", "Gmail connected. Inbox synchronization will start shortly.");
        d.put("crm.connections.googleError", "Gmail sign-in was not completed. Try again and allow both requested permissions.");
        d.put("crm.connections.remove", "Remove");
        d.put("crm.chat.subject", "Subject");
        d.put("crm.chat.from", "From");
        d.put("crm.chat.to", "To");
        d.put("crm.chat.cc", "Cc");
        d.put("crm.chat.addCc", "Add Cc");
        d.put("crm.chat.removeCc", "Remove {name} from Cc");
        d.put("crm.chat.ccInvalid", "{address} is not an email address.");
        d.put("crm.chat.ccLimit", "At most {count} people can be copied.");
        d.put("crm.chat.recipientsTo", "to");
        d.put("crm.chat.recipientsCc", "cc");
        d.put("crm.chat.me", "me");
        d.put("crm.chat.newRecipientThread", "Changing the recipient starts a new email thread.");
        d.put("crm.chat.newSubjectThread", "Changing the subject starts a new email thread.");
        d.put("crm.chat.uploadTooLarge", "{name} is too large. Maximum file size: {limit}.");
        d.put("crm.chat.dropFiles", "Drop files to attach");
        d.put("crm.chat.dropFilesHint", "Review attachments before sending your reply.");
        d.put("crm.chat.dropReplyOnly", "Switch to Reply to attach files.");
        d.put("crm.chat.uploadLimitsUnavailable", "Could not check the upload limit. Please try again.");
        d.put("crm.inbox.accounts", "Accounts");
        d.put("crm.inbox.channels", "Channels");
        d.put("crm.inbox.noAccounts", "No accounts available for this channel.");
        d.put("crm.inbox.manageConnections", "Manage connections");
        d.put("list.hideSidebar", "Hide filters");
        d.put("list.showSidebar", "Show filters");
        d.put("tags.add", "Add tag");
        d.put("tags.none", "No tags");
        d.put("tags.remove", "Remove tag {name}");
        d.put("tags.searchLabel", "Search or create tags");
        d.put("tags.searchPlaceholder", "Search or create…");
        d.put("tags.create", "Create “{name}”");
        d.put("tags.empty", "No tags yet. Type a name to create one.");
        d.put("tags.loading", "Loading tags…");
        d.put("tags.catalogEmpty", "No tags in the catalog");
        d.put("tags.updateError", "Could not update tags");
        d.put("crm.zoom.dateTime", "Date and time");
        d.put("crm.zoom.duration", "Duration (minutes)");
        d.put("crm.zoom.durationError", "Choose a duration between 5 and 240 minutes.");
        d.put("crm.zoom.hostError", "Use an HTTPS meeting link on zoom.us.");
        d.put("crm.zoom.invalidLink", "Paste a valid Zoom meeting link.");
        d.put("crm.zoom.link", "Zoom meeting link");
        d.put("crm.zoom.pastDate", "Choose a future date and time.");
        d.put("crm.zoom.save", "Save & prepare invitation");
        d.put("crm.zoom.schedule", "Schedule Zoom");
        d.put("crm.zoom.scheduleCall", "Schedule a Zoom call");
        d.put("crm.voice.play", "Play voice message");
        d.put("crm.voice.pause", "Pause voice message");
        d.put("crm.voice.seek", "Seek voice message");
        d.put("crm.voice.speed", "Playback speed: {speed}×");
        d.put("crm.voice.playbackError", "Audio could not play. Try downloading the file.");
        d.put("crm.chat.attach", "Attach files");
        d.put("crm.chat.attachLimit", "At most {count} files per message");
        d.put("crm.chat.attachTooMany", "This channel carries at most {count} files per message.");
        d.put("crm.chat.attachUnavailable", "This channel does not carry attachments.");
        d.put("crm.chat.channel", "Channel");
        d.put("crm.chat.checkAgain", "Check again");
        d.put("crm.chat.chooseConversation", "Choose a conversation to start.");
        d.put("crm.chat.conversation", "Conversation");
        d.put("crm.chat.internal", "Internal");
        d.put("crm.chat.loadEarlier", "Load earlier activity");
        d.put("crm.chat.noMessages", "No messages yet");
        d.put("crm.chat.removeFile", "Remove {name}");
        d.put("crm.chat.retryWarning", "Check the channel before retrying: an interrupted attempt may already have sent the message.");
        d.put("crm.email.frameTitle", "Email content");
        d.put("crm.email.expand", "Show full email");
        d.put("crm.email.collapse", "Collapse email");
        d.put("crm.media.viewer", "Media viewer");
        d.put("crm.media.previous", "Previous");
        d.put("crm.media.next", "Next");
        d.put("crm.media.close", "Close");
        d.put("crm.media.download", "Download");
        d.put("crm.media.open", "Open image {index} of {count}");
        d.put("crm.chat.sendFrom", "Send from");
        d.put("crm.chat.teamMember", "Team member");
        d.put("crm.chat.unknownCustomer", "Unknown customer");
        d.put("crm.chat.uploadFailed", "Upload failed");
        d.put("crm.chat.uploadFailedFile", "{name} could not be uploaded");
        d.put("crm.chat.uploading", "Uploading {count} file(s)…");
        d.put("crm.contact.close", "Close contact details");
        d.put("crm.contact.details", "Contact details");
        d.put("crm.contact.hide", "Hide contact details");
        d.put("crm.contact.open", "Open contact");
        d.put("crm.contact.show", "Show contact details");
        d.put("crm.error.action", "Action unavailable");
        d.put("crm.error.connection", "Could not check channel connection");
        d.put("crm.error.conversation", "Could not load the conversation");
        d.put("crm.error.history", "Could not load contact history");
        d.put("crm.error.noWorkspaces", "No inbox workspaces are available for your account.");
        d.put("crm.error.notes", "Could not load internal comments");
        d.put("crm.error.replyTooLong", "The reply is too long. Shorten the draft first.");
        d.put("crm.error.replyUnavailable", "Reply editing is unavailable");
        d.put("crm.error.retry", "Could not retry");
        d.put("crm.error.send", "Could not send");
        d.put("crm.inbox.account", "Account");
        d.put("crm.inbox.allAccounts", "All accounts");
        d.put("crm.inbox.allChannels", "All channels");
        d.put("crm.chat.actions", "Conversation actions");
        d.put("crm.inbox.back", "Back to conversations");
        d.put("crm.inbox.channelsAndAccounts", "Channels and accounts");
        d.put("crm.inbox.chatSelection", "Chat selection");
        d.put("crm.inbox.conversations", "Conversations");
        d.put("crm.inbox.emptyFolder", "No chats in this folder match the current filters.");
        d.put("crm.inbox.everything", "Everything");
        d.put("crm.inbox.folders", "Folders");
        d.put("crm.inbox.grouping", "Grouping");
        d.put("crm.inbox.loading", "Loading inbox…");
        d.put("crm.inbox.noMatches", "No conversations match these filters");
        d.put("crm.inbox.noMatchesHint", "Clear a filter or create a conversation.");
        d.put("crm.inbox.view", "Inbox view");
        d.put("crm.inbox.title", "Inbox");
        // The conversation actions a CRM workspace enables (CrmWorkspaceService.ACTIONS).
        d.put("crm.action.logActivity", "Log activity");
        d.put("crm.action.edit", "Open contact");
        d.put("crm.action.details", "Details");
        d.put("crm.action.reply", "Reply");
        d.put("crm.action.note", "Internal note");
        d.put("crm.action.sendReply", "Send reply");
        d.put("crm.action.sendNote", "Add note");
        d.put("crm.action.retry", "Retry");
        d.put("crm.contact.name", "Name");
        d.put("crm.contact.section", "Contact");
        // Composer: the large window (ILES-16) and drafts kept per conversation (ILES-19).
        d.put("crm.composer.expand", "Open in a larger window");
        d.put("crm.composer.maximize", "Fill the screen");
        d.put("crm.composer.restoreSize", "Restore the window size");
        d.put("crm.composer.replyTitle", "Reply to {name}");
        d.put("crm.composer.noteTitle", "Internal note about {name}");
        d.put("crm.composer.noteHint", "Visible to your team only");
        d.put("crm.composer.replyLabel", "Write a reply");
        d.put("crm.composer.sending", "Sending…");
        d.put("crm.composer.inWindow", "Writing in the larger window…");
        // Sending mailbox (ILES-17): switching to the contact's other conversations once From picks a mailbox.
        d.put("crm.chat.switchConversation", "Switch to another of this contact's conversations");
        d.put("crm.chat.switchLocked", "Send or clear your draft before changing channels");
        d.put("crm.chat.switchHint", "Choose a conversation for this contact");
        // Visible folder controls (ILES-18). Apps that call chat groups folders override crm.group.*.
        d.put("crm.chat.rowMenu", "Chat options");
        d.put("crm.group.move", "Move to group");
        d.put("crm.group.newFolder", "New group");
        d.put("crm.group.create", "Create group");
        d.put("crm.group.startsWith", "{name} moves into the new group.");
        d.put("crm.group.needsChat", "Open a chat first: a new group starts with that chat.");
        d.put("crm.inbox.listActions", "Conversation list actions");
        d.put("crm.inbox.showAll", "All chats");
        // Find in mailbox: search a connected Gmail mailbox, archived mail included, and restore a message.
        d.put("crm.mailbox.find", "Find in mailbox");
        d.put("crm.mailbox.description", "Search a connected mailbox, archived mail included, and bring a message back into the inbox.");
        d.put("crm.mailbox.account", "Mailbox");
        d.put("crm.mailbox.query", "Search the mailbox");
        d.put("crm.mailbox.placeholder", "from:anna subject:villa");
        d.put("crm.mailbox.hint", "Gmail search syntax: from:, to:, subject:, has:attachment, before:2026/01/31.");
        d.put("crm.mailbox.search", "Search");
        d.put("crm.mailbox.searching", "Searching…");
        d.put("crm.mailbox.start", "Search by sender, subject or any word in the message.");
        d.put("crm.mailbox.empty", "No messages match this search.");
        d.put("crm.mailbox.error", "Could not search this mailbox.");
        d.put("crm.mailbox.retry", "Try again");
        d.put("crm.mailbox.results", "Messages found");
        d.put("crm.mailbox.archived", "Archived");
        d.put("crm.mailbox.noSubject", "(no subject)");
        d.put("crm.mailbox.open", "Open");
        d.put("crm.mailbox.restore", "Restore to inbox");
        d.put("crm.mailbox.restoring", "Restoring…");
        d.put("crm.mailbox.restoreFailed", "Could not bring this message into the inbox.");
        d.put("crm.mailbox.readOnly", "This mailbox is connected read-only: restored mail comes into the CRM but stays archived in Gmail until the mailbox is reconnected.");
        d.put("crm.mailbox.keptArchived", "The message is in the CRM, but stays archived in Gmail until the mailbox is reconnected.");
        d.put("crm.mailbox.loadMore", "Load more");
        d.put("crm.mailbox.loadingMore", "Loading…");

        // Login screen.
        // Left-panel hero + footer copy (React shell, login.tsx) — keep in sync with DEFAULT_MESSAGES.
        d.put("login.hero.title", "Business apps shaped around roles.");
        d.put("login.hero.subtitle",
                "Sign in to see the catalogs, documents, dashboards, and forms your role is allowed to use.");
        d.put("login.footer", "Server-driven sign-in.");
        d.put("login.title", "Sign in");
        d.put("login.subtitle.password", "Use your workspace credentials.");
        d.put("login.subtitle.sso", "Continue with your organization account.");
        d.put("login.subtitle.choose", "Choose how to sign in.");
        d.put("login.sso", "Continue with {provider}");
        d.put("login.method.password", "Sign in with password");
        d.put("action.back", "Back");
        d.put("login.back", "Back");
        d.put("login.none", "No interactive login is configured for this application.");
        d.put("login.orDemo", "or use a demo account");
        d.put("login.username", "Username");
        d.put("login.password", "Password");
        d.put("login.submit", "Sign in");
        d.put("login.submitting", "Signing in...");
        d.put("login.invalid", "The username or password is not correct.");
        // Sign-in error banner, keyed by the ?error=<code> the server redirects back with (e.g. a
        // failed SSO/Telegram round-trip lands on /login?error=telegram). Keep in sync with the
        // frontend DEFAULT_MESSAGES.
        d.put("login.error.telegram", "Telegram sign-in failed. Please try again.");
        d.put("login.error.access_denied", "This account isn't authorized to use this app.");
        d.put("login.error.session_expired", "Your session expired — please sign in again.");
        d.put("login.error.sso", "Single sign-on failed. Please try again.");
        d.put("login.error.generic", "Sign-in failed. Please try again.");

        // Action buttons / row-menu items.
        d.put("action.new", "New");
        d.put("action.add", "Add");
        d.put("form.tabularKeyboardHint", "Tab to move between fields · Enter to continue from a text or number field");
        d.put("form.rowActions", "Row actions");
        d.put("form.rowField", "Row {row}: {field}");
        d.put("form.removeRow", "Remove row {row}");
        d.put("form.rowRemoved", "Row removed");
        d.put("form.undoRemoveRow", "Undo remove");
        d.put("action.addRow", "Add row");
        d.put("action.cancel", "Cancel");
        d.put("action.create", "Create");
        d.put("action.save", "Write");
        d.put("action.saving", "Saving…");
        d.put("action.post", "Post");
        d.put("action.repost", "Re-post");
        d.put("action.unpost", "Unpost");
        d.put("action.duplicate", "Duplicate");
        d.put("action.openInList", "Open in list");
        d.put("action.delete", "Delete");
        d.put("action.open", "Open");
        d.put("action.copyLink", "Copy link");
        d.put("workspace.back", "Back");
        d.put("workspace.forward", "Forward");
        d.put("workspace.openInNewTab", "Open in new tab");
        d.put("workspace.closeOtherTabs", "Close other tabs");
        d.put("workspace.newTab", "New tab");
        d.put("workspace.openBeside", "Open beside");
        d.put("workspace.newTab.search", "Search screens…");
        d.put("workspace.newTab.screens", "Go to");
        d.put("workspace.newTab.create", "Create");
        d.put("workspace.newTab.alreadyOpen", "Open");
        d.put("workspace.newTab.empty", "Nothing found");

        // Record change history panel (@Catalog/@Document(history = true)).
        d.put("history.title", "History");
        d.put("history.loading", "Loading…");
        d.put("history.empty", "No changes recorded yet.");
        d.put("history.loadError", "Could not load the history.");
        d.put("history.more", "Show older changes");
        d.put("history.event.CREATED", "Created");
        d.put("history.event.UPDATED", "Changed");
        d.put("history.event.DELETED", "Marked for deletion");
        d.put("history.event.RESTORED", "Restored");
        d.put("history.event.POSTED", "Posted");
        d.put("history.event.UNPOSTED", "Unposted");
        d.put("history.actor.system", "System");
        d.put("history.secret", "changed (value hidden)");
        d.put("history.row.ADDED", "{section}, line {line} added");
        d.put("history.row.REMOVED", "{section}, line {line} removed");
        d.put("history.row.CHANGED", "{section}, line {line}");
        d.put("history.field.code", "Code");
        d.put("history.field.description", "Description");
        d.put("history.field.parent", "Parent");
        d.put("history.field.folder", "Folder");
        d.put("history.field.number", "Number");
        d.put("history.field.date", "Date");
        d.put("history.value.yes", "Yes");
        d.put("history.value.no", "No");
        d.put("history.value.empty", "empty");

        d.put("action.close", "Close");
        d.put("window.minimize", "Minimize");
        d.put("window.restore", "Restore");
        d.put("window.maximize", "Maximize");
        d.put("window.dock", "Dock to the corner");
        d.put("window.float", "Float freely");
        d.put("action.ok", "OK");
        d.put("action.feedback.blocked", "Action blocked");
        d.put("action.feedback.completed", "Action completed");

        // Calendar/date controls (React date inputs and dashboard calendar widgets).
        d.put("calendar.today", "Today");
        d.put("calendar.month", "Month");
        d.put("calendar.week", "Week");
        d.put("calendar.previous", "Previous");
        d.put("calendar.next", "Next");
        d.put("calendar.monthPicker", "Month");
        d.put("calendar.yearPicker", "Year");
        d.put("calendar.open", "Open calendar");
        d.put("calendar.readOnly", "read only");
        d.put("calendar.emptyThisMonth", "No {name} this month.");
        d.put("calendar.agenda", "Agenda");
        d.put("calendar.allDay", "All day");
        d.put("calendar.moveFailed", "Could not reschedule this item.");
        d.put("calendar.jumpTo", "Go to a month or year");
        d.put("calendar.back", "Back");
        d.put("calendar.itemCount", "{count} planned");
        d.put("calendar.year", "Year");
        d.put("calendar.close", "Close");
        d.put("calendar.when", "When");
        d.put("calendar.openItem", "Open");
        d.put("calendar.minutes", "{count} min");
        d.put("calendar.hours", "{count} h");

        // Calendar / timeline list views (ListSpec.calendar / ListSpec.timeline).
        d.put("list.calendarView", "Calendar");
        d.put("list.timelineView", "Timeline");
        d.put("schedule.day", "Day");
        d.put("schedule.unassigned", "Unassigned");
        d.put("schedule.empty", "Nothing scheduled in this period.");
        d.put("schedule.showingFirst", "Showing the first {shown} records of this period.");
        d.put("schedule.resize", "Change the end time");

        // Time-range picker chrome (dashboard shared date-range control).
        d.put("timeRange.dateRange", "Date range");
        d.put("timeRange.custom", "Custom");
        d.put("timeRange.allTime", "All time");
        d.put("timeRange.lastSecond", "Last second");
        d.put("timeRange.lastSeconds", "Last {n} seconds");
        d.put("timeRange.lastMinute", "Last minute");
        d.put("timeRange.lastMinutes", "Last {n} minutes");
        d.put("timeRange.lastHour", "Last hour");
        d.put("timeRange.lastHours", "Last {n} hours");
        d.put("timeRange.lastDay", "Last day");
        d.put("timeRange.lastDays", "Last {n} days");
        d.put("timeRange.lastWeek", "Last week");
        d.put("timeRange.lastWeeks", "Last {n} weeks");
        d.put("timeRange.lastMonth", "Last month");
        d.put("timeRange.lastMonths", "Last {n} months");
        d.put("timeRange.lastYear", "Last year");
        d.put("timeRange.lastYears", "Last {n} years");
        d.put("timeRange.granularity.label", "Interval");
        d.put("timeRange.granularity.autoResolved", "Auto ({value})");
        d.put("timeRange.granularity.minute", "1 min");
        d.put("timeRange.granularity.hour", "1 hour");
        d.put("timeRange.granularity.day", "1 day");
        d.put("timeRange.granularity.week", "1 week");
        d.put("timeRange.granularity.month", "1 month");

        // Shared select/bottom-sheet accessibility labels.
        d.put("select.option", "Select option");

        // Document posting status.
        d.put("status.posted", "Posted");
        d.put("status.draft", "Draft");

        // Workspace tab titles. The list tab reads the entity's localized title directly (from the
        // shell's path→title map); these template the new/duplicate record tabs around it.
        d.put("tab.new", "New {entity}");
        d.put("tab.duplicate", "Duplicate {entity}");

        // Navigation. The home/dashboard nav item (and its open-tab chip) falls back to this when no
        // authored Page sets a title; an app overrides it via onno.ui.messages for a localized shell.
        // (Settings has no built-in nav entry — an app authors a Page at "/settings" and links it with
        // its own label via section(...).page("/settings", "…", "settings").)
        d.put("nav.dashboard", "Dashboard");
        d.put("home.greeting", "Welcome back, {name}");

        // Notification center (bell beside the profile + right slide-over). Mirror messages.ts DEFAULT_MESSAGES.
        d.put("notifications.title", "Notifications");
        d.put("notifications.empty", "You're all caught up");
        d.put("notifications.markAllRead", "Mark all read");
        d.put("notifications.all", "All");
        d.put("notifications.unread", "Unread");
        d.put("notifications.typeAll", "All");
        d.put("notifications.typeMention", "Mentions");
        d.put("notifications.typeAssignment", "Assigned");
        d.put("notifications.typeReply", "Replies");
        d.put("notifications.today", "Today");
        d.put("notifications.thisWeek", "Earlier this week");
        d.put("notifications.older", "Older");

        // App shell / account island.
        d.put("shell.signedInAs", "Signed in as");
        d.put("shell.accountMenu", "Account menu");
        d.put("shell.appearance", "Appearance");
        d.put("shell.theme", "Theme");
        d.put("shell.themeLight", "Light");
        d.put("shell.themeDark", "Dark");
        d.put("shell.themeSystem", "System");
        d.put("shell.signOut", "Sign out");
        d.put("shell.menu", "Menu");
        d.put("shell.more", "More");
        d.put("shell.collapseNavigation", "Collapse navigation");
        d.put("shell.expandNavigation", "Expand navigation");

        // Register surface (the virtualized movements/balance lists).
        d.put("register.records", "History");
        d.put("register.latest", "Latest values");
        d.put("register.date", "Date");
        d.put("register.period", "Period");
        d.put("register.type", "Type");
        d.put("register.balanceTab", "Balance");
        d.put("register.movementsTab", "Movements");
        d.put("register.receipt", "Receipt");
        d.put("register.expense", "Expense");

        // List grid: toolbar count, search, sort, view toggle, and keyset loading.
        d.put("list.count", "Rows: {count}");
        d.put("list.focusMissing", "This record isn't in the list with the current filters");
        d.put("list.search", "Search…");
        d.put("list.export", "Export");
        d.put("list.export.failed", "Could not export the list");
        d.put("list.export.tooManyRows", "Too many rows to export. Narrow the results and try again.");
        d.put("list.export.textTooLong", "A value is too long for an XLSX cell. Export as CSV.");
        d.put("list.searchLabel", "Search");
        d.put("list.all", "All");
        d.put("list.sortBy", "Sort by {column}");
        d.put("list.tableView", "Table");
        d.put("list.mapView", "Map");
        // Fallback toggle label for a custom list view (`list.custom`) without a label.
        d.put("list.customView", "Cards");
        d.put("list.loadingMore", "Loading more…");
        // Batch selection (⌘/Ctrl-click, Shift-click) and its context-menu operations. The
        // selection count itself reuses "list.selected" below (shared with the filter badge).
        d.put("list.clearSelection", "Clear selection");
        d.put("list.selectLoaded", "Select loaded rows");
        d.put("list.selectAll", "Select all matching rows");
        d.put("list.selectingAll", "Selecting all matching rows…");
        d.put("list.selectAllFailed", "Could not select all matching rows. Try again.");
        d.put("list.selectRow", "Select {row}");
        d.put("list.selectionActions", "Actions for selected");
        d.put("batch.delete", "Delete {n}");
        d.put("batch.deleteConfirm", "Sure? Delete {n}");
        d.put("batch.running", "{label} — running on {n}…");
        d.put("batch.done", "{label}: {ok}/{n} done");
        d.put("list.download.running", "Preparing {label} for {n}…");
        d.put("list.download.failed", "{label} failed");
        // onno-print-starter's printTemplates widget (template management for admins).
        d.put("print.templates.title", "Print templates");
        d.put("print.templates.empty", "No print forms are registered.");
        d.put("print.templates.form", "Form");
        d.put("print.templates.entity", "Record type");
        d.put("print.templates.template", "Template");
        d.put("print.templates.builtIn", "Built-in");
        d.put("print.templates.uploaded", "Uploaded {date} by {user}");
        d.put("print.templates.download", "Download");
        d.put("print.templates.downloadBuiltIn", "Download built-in");
        d.put("print.templates.upload", "Upload");
        d.put("print.templates.reset", "Reset");
        d.put("print.templates.uploadedToast", "Template for {form} uploaded");
        d.put("print.templates.resetToast", "{form} uses its built-in template again");
        d.put("print.templates.loadFailed", "Could not load print templates.");
        d.put("print.templates.disabled", "Template uploads are disabled (onno.print.templates.enabled=false).");
        d.put("print.templates.hint", "Download a template, edit it in Word or Excel keeping the {{placeholders}}, then upload it. Reset restores the built-in file.");
        d.put("batch.deleted", "Deleted {ok}/{n}");
        // Row clipboard: ⌘C copies rows (TSV + app payload), ⌘V pastes them back as new records.
        d.put("clipboard.copied", "{count} copied");
        d.put("clipboard.pasted", "Pasted {ok}/{n}");
        d.put("clipboard.tooMany", "Paste limited to {max} records at a time");
        // Appended to a duplicated catalog record's description (⌘V / POST …/duplicate); blank disables.
        d.put("duplicate.copySuffix", " (copy)");
        // ⌘A in an infinite feed can only select what's loaded; hint when more rows exist server-side.
        d.put("list.selectedPartial", "Selected {count} loaded rows — more exist, scroll to load them");
        // Closing a form with unsaved edits.
        d.put("confirm.discard.title", "Discard changes?");
        d.put("confirm.discard.message", "This form has unsaved changes. Close it and discard them?");
        d.put("action.discard", "Discard");
        d.put("list.groupBy", "Group by");
        d.put("list.groupNone", "None");
        d.put("list.groupByHint", "Group rows by a column");
        d.put("list.showMore", "Show more");
        d.put("list.groupsCapped", "Showing the first groups only — narrow with a filter to see the rest.");
        d.put("list.granDay", "Day");
        d.put("list.granMonth", "Month");
        d.put("list.granYear", "Year");
        // Faceted filter bar: chip labels, date-range presets, clear-all.
        d.put("list.filters", "Filters");
        d.put("list.filterHint", "Filter by {label}");
        d.put("list.clear", "Clear");
        d.put("list.clearAll", "Clear all");
        d.put("list.done", "Done");
        d.put("list.rangeFrom", "From");
        d.put("list.rangeTo", "To");
        d.put("list.selected", "{count} selected");
        d.put("list.dateToday", "Today");
        d.put("list.dateYesterday", "Yesterday");
        d.put("list.dateLast7", "Last 7 days");
        d.put("list.dateLast30", "Last 30 days");
        d.put("list.dateThisMonth", "This month");
        d.put("list.dateThisYear", "This year");

        // List map view: the floating count chip and the marker popup.
        d.put("map.count", "{count} on the map");
        d.put("map.noRecords", "No records with a location.");
        d.put("map.showingFirst", "showing the first {shown} of {total}");
        d.put("map.recordsHere", "{count} records here");
        d.put("map.more", "+{count} more");

        // Pane-level load failures (the web client's content-pane error surface), shaped by HTTP
        // status. "forbidden" is the access-denied surface a user sees opening an entity their role
        // can't read; "notFound" a route with no view (or a stale link); "unauthorized" a dead
        // session. Mirror messages.ts DEFAULT_MESSAGES.
        d.put("error.forbidden.title", "No access");
        d.put("error.forbidden.body",
                "Your role doesn't have permission to view this page. Ask an administrator if you think it should.");
        d.put("error.notFound.title", "Page not found");
        d.put("error.notFound.body", "This page doesn't exist, or it isn't available for your role.");
        d.put("error.unauthorized.title", "Signed out");
        d.put("error.unauthorized.body", "Your session has expired. Sign in again to continue.");
        d.put("error.generic.title", "Something went wrong");
        d.put("error.generic.body", "The page couldn't be loaded. This may be temporary — try again.");
        d.put("error.retry", "Try again");
        d.put("error.home", "Go to home");
        d.put("error.signIn", "Sign in");

        // Empty / loading states.
        d.put("empty.noRecords", "No records");
        d.put("empty.noMatches", "No matches");
        d.put("empty.noRows", "No rows yet.");
        d.put("empty.noData", "No data yet");
        d.put("loading.workspace", "Loading workspace...");
        d.put("loading.generic", "Loading…");
        d.put("loading.searching", "Searching…");
        d.put("loading.working", "Working…");

        // Success toasts.
        d.put("toast.posted", "Document posted");
        d.put("toast.unposted", "Document unposted");
        d.put("toast.saved", "Saved");

        // Delete-confirmation dialogs.
        d.put("confirm.delete.document.title", "Delete document?");
        d.put("confirm.delete.document.message",
                "This document will be marked for deletion. You can't undo this from here.");
        d.put("confirm.delete.item.title", "Delete item?");
        d.put("confirm.delete.item.message",
                "This item will be marked for deletion. You can't undo this from here.");

        // Forms / ref pickers.
        d.put("form.saveError", "Couldn't save: {error}");
        d.put("form.select", "Select {name}…");
        d.put("form.clearSelection", "Clear selection");
        d.put("form.noSelection", "No selection");
        d.put("form.hexColor", "Hex color");
        d.put("form.chooseColor", "Choose color");
        d.put("ref.selectedElsewhere", "Selected in another row");
        // A reference to a record the viewer may not open (record-level access policies).
        d.put("ref.restricted", "Restricted");
        d.put("ref.restrictedHint", "You don't have access to this record");
        d.put("ref.alreadySelected", "Already selected");
        // Shown on an open form when the record changes elsewhere (another user/tab/widget) over SSE.
        d.put("form.staleChanged", "This record changed elsewhere.");
        d.put("form.reload", "Reload");
        d.put("form.newTitle", "New {name}");
        d.put("form.duplicateTitle", "Duplicate {name}");
        d.put("form.tabularEmptyHint", "Add the first row to start filling this section.");
        d.put("ref.new", "New {name}");
        d.put("ref.recent", "Recent");
        d.put("ref.open", "Open record");
        d.put("ref.clearValue", "Clear value");
        d.put("ref.showAll", "Show all…");
        d.put("ref.pickTitle", "Select: {name}");
        d.put("ref.pick", "Select");
        d.put("ref.pickFiltered", "The list is limited by this field's conditions");
        d.put("ref.folder", "Folder");
        d.put("ref.folders", "Folders");
        d.put("ref.location", "Location");
        d.put("ref.pickEmpty", "Nothing to select here");

        // Client-side validation messages (mirrors the server's AttributeValidator).
        // Dashboard widgets — keep in sync with DEFAULT_MESSAGES. The stat tile's
        // period-over-period caption; a widget can override it per tile with
        // .config("comparisonLabel", ...).
        d.put("widget.stat.comparison", "vs previous period");
        d.put("widget.records.truncated", "Showing the first {count} records. More records match this query.");

        d.put("validation.required", "{field} is required");
        d.put("validation.maxLength", "{field} must be at most {n} characters");
        d.put("validation.minLength", "{field} must be at least {n} characters");
        d.put("validation.pattern", "{field} is not in the expected format");
        d.put("validation.email", "{field} must be a valid email address");
        d.put("validation.hexColor", "{field} must be a hex color such as #AABBCC");
        d.put("validation.min", "{field} must be at least {n}");
        d.put("validation.max", "{field} must be at most {n}");

        DEFAULTS = Collections.unmodifiableMap(d);
    }

    /** Shared instance with no overrides — the back-compat default for builders/tests. */
    private static final UiMessages DEFAULT_INSTANCE = new UiMessages(Map.of());

    private final Map<String, String> resolved;

    /** Builds the resolved map: the English {@link #DEFAULTS} with the given overrides layered on top. */
    public UiMessages(Map<String, String> overrides) {
        Map<String, String> m = new LinkedHashMap<>(DEFAULTS);
        if (overrides != null) {
            overrides.forEach((k, v) -> {
                if (k != null && v != null) {
                    m.put(k, v);
                }
            });
        }
        this.resolved = Collections.unmodifiableMap(m);
    }

    /** The all-defaults instance — used by builder overloads and unit tests. */
    public static UiMessages defaults() {
        return DEFAULT_INSTANCE;
    }

    /** The resolved text for {@code key}, or the key itself when unknown (so a typo is visible, not blank). */
    public String get(String key) {
        return resolved.getOrDefault(key, key);
    }

    /**
     * {@link #get(String)} with {@code {placeholder}} substitution: {@code format("ref.new", "name",
     * "Customers")} → {@code "New Customers"}. Args are alternating placeholder/value pairs.
     */
    public String format(String key, Object... placeholderValues) {
        String template = get(key);
        for (int i = 0; i + 1 < placeholderValues.length; i += 2) {
            template = template.replace("{" + placeholderValues[i] + "}", String.valueOf(placeholderValues[i + 1]));
        }
        return template;
    }

    /** The full resolved map — handed to the web client via {@code GET /api/config}. */
    public Map<String, String> asMap() {
        return resolved;
    }
}
