import { Button, CommentBody, ExtensionSlot, text as t, type ExtensionContext } from "@onno/ui";
import { Activity, Paperclip } from "@onno/ui/icons";
import { Fragment, type ReactNode } from "react";
import { CallActivityCard, callActivity } from "./CallActivityCard";
import { ChannelLogo } from "./ChannelLogo";
import { ContactAvatarImage } from "./ContactAvatarImage";
import { EmailHtmlBody } from "./EmailHtmlBody";
import { LinkedText } from "./LinkedText";
import { MediaGrid } from "./MediaGrid";
import { VoicePlayer } from "./VoicePlayer";
import { ChatMessageBody } from "./chatMessageRenderers";
import { action, type Config } from "./CrmWorkspace";
import { fileSize, fullTime, initials, type CrmAttachment, type CrmMessage, type CrmNote, type CrmTimelineEntry } from "./crmModel";
import { addressLabel, parseAddress, splitAddresses } from "./emailAddresses";

/**
 * The chat's building blocks: avatars, message bubbles, notes, system events, and the timeline that
 * lays them out. Each is exported, so an app can draw a conversation its own way and still reuse the
 * parts it likes; `ChatTimeline`'s `renderEntry` swaps any single entry without forking the rest.
 */

/** A round avatar with initials under the photo, and the channel as a corner badge. */
export function CrmAvatar({ name, url, channel, className = "size-8" }: {
  name: string; url?: string | null; channel?: string | null; className?: string;
}) {
  return (
    <span className={`relative ${className} shrink-0`}>
      <span className="relative flex size-full items-center justify-center overflow-hidden rounded-full border border-border bg-muted text-[10px] font-semibold text-foreground">
        {initials(name)}
        <ContactAvatarImage name={name} url={url} />
      </span>
      {channel ? <ChannelIcon channel={channel} avatarBadge /> : null}
    </span>
  );
}

export function ChannelIcon({ channel, comment = false, avatarBadge = false }: { channel: string; comment?: boolean; avatarBadge?: boolean }) {
  return <span title={comment ? "Internal note" : channel} className={avatarBadge
    ? "absolute -bottom-0.5 -right-0.5 z-10 flex size-[18px] items-center justify-center rounded-full bg-card ring-2 ring-card"
    : "flex size-6 shrink-0 items-center justify-center"}>
    <ChannelLogo channel={comment ? "internal" : channel} className={avatarBadge ? "size-3.5" : "size-4"} />
  </span>;
}

/**
 * Non-image files on a message: a player for audio, a download chip for everything else. Images are
 * drawn separately, as one album above the caption ({@link MediaGrid}).
 */
export function MessageAttachments({ files, outbound }: { files: CrmAttachment[]; outbound: boolean }) {
  if (!files.length) return null;
  return (
    <div className="mt-1.5 flex flex-col gap-1.5">
      {files.map(file => /^audio\//i.test(file.contentType.trim()) ? (
        <VoicePlayer key={file.url} src={file.url} label={file.filename} download={file.filename} outbound={outbound} />
      ) : (
        <a key={file.url} href={file.url} target="_blank" rel="noreferrer" download={file.filename}
          className={outbound
            ? "flex items-center gap-2 rounded-field bg-primary-foreground/15 px-2.5 py-2 text-[12px] hover:bg-primary-foreground/25"
            : "flex items-center gap-2 rounded-field bg-muted px-2.5 py-2 text-[12px] hover:bg-muted/70"}>
          <Paperclip className="size-3.5 shrink-0" aria-hidden="true" />
          <span className="min-w-0 flex-1 truncate font-medium">{file.filename}</span>
          {file.size > 0 && <span className="shrink-0 tabular-nums opacity-70">{fileSize(file.size)}</span>}
        </a>
      ))}
    </div>
  );
}

/** How many people a recipients line names before it says "+N". */
const NAMED_RECIPIENTS = 3;

/**
 * Who else was on an email, as one muted line: "to Sarah, me · cc Marco, Giulia". Drawn only when the email had a
 * Cc or several To, so ordinary one-to-one mail stays as it was. Names when the headers gave them, else addresses;
 * a long list ends in "+N", and the full lists are the line's tooltip. "me" is the conversation's own mailbox.
 */
export function MessageRecipients({ message, outbound }: { message: Pick<CrmMessage, "sentTo" | "copyTo" | "accountLabel">; outbound: boolean }) {
  const to = splitAddresses(message.sentTo), cc = splitAddresses(message.copyTo);
  if (!cc.length && to.length < 2) return null;
  const own = new Set((message.accountLabel ?? "").toLowerCase().split(/[\s·<>,;]+/).filter(Boolean));
  const label = (entry: string) => own.has(parseAddress(entry).address.toLowerCase()) ? t("crm.chat.me") : addressLabel(entry);
  const list = (entries: string[]) => entries.length <= NAMED_RECIPIENTS ? entries.map(label).join(", ")
    : `${entries.slice(0, NAMED_RECIPIENTS - 1).map(label).join(", ")} +${entries.length - NAMED_RECIPIENTS + 1}`;
  const line = [to.length ? `${t("crm.chat.recipientsTo")} ${list(to)}` : null, cc.length ? `${t("crm.chat.recipientsCc")} ${list(cc)}` : null].filter(Boolean).join(" · ");
  const title = [to.length ? `${t("crm.chat.to")}: ${to.join(", ")}` : null, cc.length ? `${t("crm.chat.cc")}: ${cc.join(", ")}` : null].filter(Boolean).join("\n");
  return <p data-message-recipients="" title={title}
    className={`mt-0.5 truncate text-[11px] ${outbound ? "text-primary-foreground/75" : "text-muted-foreground"}`}>{line}</p>;
}

/** The `crm.chat.message.actions` outlet's context for one message: the conversation's, plus the message. */
export type MessageActionsContext = Omit<ExtensionContext, "record" | "recordId">;

/**
 * A customer message or an agent reply. `messageContext` turns on the `crm.chat.message.actions`
 * outlet under the bubble (quote, forward, create a task from it, …); it gets the message as `record`.
 */
export function TimelineMessage({ message, avatarUrl, retry, busy, config, channel, messageContext }: {
  channel?: string; config?: Config; message: CrmMessage; avatarUrl?: string | null; retry: () => void; busy: boolean;
  messageContext?: MessageActionsContext;
}) {
  const outbound = message.direction === "OUTBOUND";
  const html = message.bodyHtml?.trim() ? message.bodyHtml : null;
  // Inline email images already appear inside the HTML; list only the files it does not show.
  const files = (message.attachments ?? []).filter(file => !html || !html.includes(file.url));
  const images = files.filter(file => file.image);
  const others = files.filter(file => !file.image);
  const recipients = splitAddresses(message.copyTo).length > 0 || splitAddresses(message.sentTo).length > 1
    ? <MessageRecipients message={message} outbound={outbound} /> : null;
  return (
    <div data-chat-message={message.id} className={outbound ? "group/message flex flex-row-reverse items-end gap-2" : "group/message flex items-end gap-2"}>
      {config?.showAvatar !== false && <CrmAvatar name={message.authorName} url={avatarUrl} channel={channel || message.channel} />}
      <div className={(outbound
        ? "min-w-0 max-w-[85%] break-words rounded-panel rounded-br-field bg-primary px-3.5 py-2.5 text-primary-foreground"
        : "min-w-0 max-w-[85%] break-words rounded-panel rounded-bl-field border border-border bg-card px-3.5 py-2.5 text-foreground")
        + (html ? " w-full sm:w-[85%]" : "")}
      >
        <div className="flex items-center gap-1.5">
          <span className={outbound ? "text-[10px] font-semibold text-primary-foreground/75" : "text-[10px] font-semibold text-muted-foreground"}>
            {message.authorName}
          </span>
        </div>
        {/* The recipients line names the recipient itself when it is drawn. */}
        {outbound && (message.subject || (message.recipient && !recipients)) ? <p className="mt-0.5 truncate text-[11px] text-primary-foreground/75">
          {[!recipients && message.recipient && `→ ${message.recipient}`, message.subject].filter(Boolean).join(" · ")}
        </p> : null}
        {recipients}
        <MediaGrid images={images} />
        <ChatMessageBody message={message} fallback={html ? <EmailHtmlBody html={html} /> : message.body?.trim()
          ? <p className="mt-1 whitespace-pre-wrap text-sm leading-6"><LinkedText text={message.body} /></p> : null} />
        <MessageAttachments files={others} outbound={outbound} />
        <div className={outbound
          ? "mt-1 flex justify-end gap-1.5 text-[10px] text-primary-foreground/70"
          : "mt-1 flex justify-end gap-1.5 text-[10px] text-muted-foreground"}
        >
          {config?.showTimestamps !== false && <time>{fullTime(message.sentAt)}</time>}
          {outbound && config?.showDeliveryStatus !== false ? <span>{["SENT", "DELIVERED"].includes(message.deliveryStatus) ? "✓ " : ""}{message.deliveryStatus.toLowerCase()}</span> : null}
          {outbound && message.deliveryStatus === "FAILED" && action(config, "retry") ? (
            <Button variant="ghost" type="button" disabled={busy} onClick={retry} className="h-auto p-0 text-inherit underline disabled:opacity-50"
              title={t("crm.chat.retryWarning")}>{action(config, "retry")?.label}</Button>
          ) : null}
        </div>
      </div>
      {messageContext && <ExtensionSlot name="crm.chat.message.actions"
        className="flex shrink-0 items-center gap-1 self-center opacity-0 transition-opacity group-hover/message:opacity-100 group-focus-within/message:opacity-100"
        context={{ ...messageContext, recordId: message.id, record: message as unknown as Record<string, unknown> }} />}
    </div>
  );
}

/** A system event (status changed, call logged, …) as a centred line, or a call card. */
export function TimelineSystemEvent({ message, config }: { message: CrmMessage; config?: Config }) {
  const activity = callActivity(message.body);
  if (activity) return <CallActivityCard activity={activity} author={message.authorName} at={config?.showTimestamps !== false ? fullTime(message.sentAt) : undefined} />;
  return (
    <div className="flex items-center justify-center gap-2 px-6 py-1 text-center text-[10px] text-muted-foreground">
      <span className="h-px min-w-5 flex-1 bg-border" />
      <span className="inline-flex max-w-[78%] items-center gap-1.5 rounded-pill bg-muted px-2.5 py-1">
        <Activity size={12} className="shrink-0 text-primary" aria-hidden="true" />
        <span className="whitespace-pre-wrap break-words text-left"><CommentBody body={message.body} /></span>
        {config?.showTimestamps !== false && <time className="shrink-0 text-muted-foreground/70">{fullTime(message.sentAt)}</time>}
      </span>
      <span className="h-px min-w-5 flex-1 bg-border" />
    </div>
  );
}

/** An internal note: tinted, and labelled as visible to the team only. */
export function TimelineComment({ comment, config }: { comment: CrmNote; config?: Config }) {
  const author = comment.authorName || t("crm.chat.teamMember");
  return (
    <div className={comment.mine ? "flex flex-row-reverse items-end gap-2" : "flex items-end gap-2"}>
      {config?.showAvatar !== false && <CrmAvatar name={author} url={comment.authorAvatarUrl} />}
      <div className={comment.mine
        ? "min-w-0 max-w-[85%] break-words rounded-panel rounded-br-field border border-amber-400/45 bg-amber-400/10 px-3.5 py-2.5 text-foreground"
        : "min-w-0 max-w-[85%] break-words rounded-panel rounded-bl-field border border-amber-400/45 bg-amber-400/10 px-3.5 py-2.5 text-foreground"}
      >
        <div className="flex items-center gap-1.5">
          <ChannelIcon channel="" comment />
          <span className="text-[10px] font-semibold text-amber-700 dark:text-amber-300">{author}</span>
          <span className="text-[9px] font-medium uppercase tracking-wide text-muted-foreground">{t("crm.chat.internal")}</span>
        </div>
        <p className="mt-1 whitespace-pre-wrap text-sm leading-6"><CommentBody body={comment.body} /></p>
        <time className="mt-1 block text-right text-[10px] text-muted-foreground">
          {config?.showTimestamps !== false && comment.createdAt ? fullTime(comment.createdAt) : ""}
        </time>
      </div>
    </div>
  );
}

/** Props of {@link ChatTimeline}. */
export type ChatTimelineProps = {
  entries: readonly CrmTimelineEntry[];
  config?: Config;
  /** The customer's photo, for their messages. */
  customerAvatarUrl?: string | null;
  /** Disables per-message commands (retry) while something else is in flight. */
  busy?: boolean;
  onRetry?: (message: CrmMessage) => void;
  /**
   * Draw one entry your own way, or drop it (return null). `render()` is the default drawing, so
   * an app can wrap it (a highlight, a divider) instead of replacing it.
   */
  renderEntry?: (entry: CrmTimelineEntry, render: () => ReactNode) => ReactNode;
  /** Turns on the per-message `crm.chat.message.actions` outlet. */
  messageContext?: MessageActionsContext;
};

/**
 * A conversation's entries in order: messages, notes, system events, and the app's own entries
 * (`timelineSources`). Renders the list only; the scrolling container is the caller's.
 */
export function ChatTimeline({ entries, config, customerAvatarUrl, busy = false, onRetry, renderEntry, messageContext }: ChatTimelineProps) {
  const defaultRender = (entry: CrmTimelineEntry): ReactNode => {
    if (entry.type === "custom") return entry.render();
    if (entry.type === "note") return <TimelineComment comment={entry.note} config={config} />;
    const message = entry.message;
    if (message.kind === "SYSTEM_EVENT") return (config?.showSystemEvents ?? true)
      ? <ChatMessageBody message={message} fallback={<TimelineSystemEvent message={message} config={config} />} /> : null;
    return <TimelineMessage config={config} message={message} channel={message.channel}
      retry={() => onRetry?.(message)} busy={busy}
      avatarUrl={message.direction === "INBOUND" ? customerAvatarUrl : message.authorAvatarUrl}
      messageContext={messageContext} />;
  };
  return <>{entries.map(entry => {
    const node = renderEntry ? renderEntry(entry, () => defaultRender(entry)) : defaultRender(entry);
    return node == null || node === false ? null : <Fragment key={entry.id}>{node}</Fragment>;
  })}</>;
}
