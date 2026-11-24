import { Button, ExtensionSlot, FloatingWindow, Input, ResponsiveActionPanel, Segmented, Select, SelectContent, SelectItem, SelectTrigger, SelectValue, Textarea,
  WorkspaceToolbar, text as t, type EntityRecord, type ExtensionContext, type WorkspaceToolbarLayout } from "@onno/ui";
import { Maximize2, Paperclip, PenLine, Plus, RefreshCw, Send, X } from "@onno/ui/icons";
import { useEffect, useId, useRef, useState, type ReactNode, type Ref } from "react";
import { ChannelLogo } from "./ChannelLogo";
import { LogActivity } from "./LogActivity";
import { NoteInput } from "./NoteInput";
import { ScheduleCall } from "./ScheduleCall";
import { action, type Config } from "./CrmWorkspace";
import { conversationChannel, fileSize, string } from "./crmModel";
import { composerDrafts, type ComposerMode } from "./composerDrafts";
import { useComposerWindowHost, useComposerWindows } from "./composerWindows";
import { useAutoGrow } from "./useAutoGrow";
import type { CrmComposerState } from "./useCrmComposer";
import type { CrmConversationState } from "./useCrmConversation";

/** Puts the caret at the end of the composer field in `scope`, the way a returning writer expects. */
function focusComposerField(scope: ParentNode | null | undefined, mode: ComposerMode) {
  const field = scope?.querySelector<HTMLElement>(mode === "note" ? "[data-composer-note] textarea" : "[data-composer-reply]");
  if (!field || field.getAttribute("aria-disabled") === "true" || field.matches(":disabled")) return;
  field.focus({ preventScroll: true });
  if (field instanceof HTMLTextAreaElement) field.setSelectionRange(field.value.length, field.value.length);
  else {
    const range = document.createRange();
    range.selectNodeContents(field); range.collapse(false);
    const selection = window.getSelection();
    selection?.removeAllRanges(); selection?.addRange(range);
  }
}

const selectTriggerClass = "h-9 w-auto max-w-full min-w-0 gap-2 rounded-field border-0 bg-transparent px-1 text-xs shadow-none hover:bg-muted focus:ring-1";

/**
 * Where a reply goes out from: the sending mailbox (when the channel offers a choice) and the
 * contact's other conversations to switch to (another channel or mailbox thread).
 */
export function SenderControls({ conversation, composer, siblings = [], onSwitch, labels }: {
  conversation: CrmConversationState; composer: CrmComposerState;
  /** The contact's conversations this reply may go through instead (including the current one). */
  siblings?: readonly EntityRecord[];
  onSwitch?: (conversationId: string) => void;
  /** Labelled rows ("From", "Channel") rather than a compact line. */
  labels: boolean;
}) {
  const selected = conversation.conversation;
  if (!selected) return null;
  const { delivery } = conversation;
  const options = siblings.length ? siblings : [selected];
  const identity = <span className="flex min-w-0 items-center gap-2"><ChannelLogo channel={conversationChannel(selected)} className="size-4" /><span className="truncate">{string(selected, "inboxDisplay", delivery?.label || conversationChannel(selected))}</span></span>;
  const conversationSwitch = (label: string) => options.length === 1 || !onSwitch ? <span className="flex h-9 min-w-0 items-center px-1">{identity}</span> : (
    <Select value={conversation.id || ""} disabled={composer.busy || !!composer.draft.trim() || composer.subjectDraft !== null || (composer.recipientDraft !== null && composer.recipientDraft !== delivery?.recipient) || composer.mode === "note"}
      onValueChange={(id: string) => onSwitch(id)}>
      <SelectTrigger aria-label={label} className={selectTriggerClass} title={composer.draft.trim() ? t("crm.chat.switchLocked") : t("crm.chat.switchHint")}>
        <SelectValue>{identity}</SelectValue>
      </SelectTrigger>
      <SelectContent>
        {options.map(row => (
          <SelectItem key={String(row.id)} value={String(row.id)}>
            <span className="flex min-w-0 items-center gap-2">
              <ChannelLogo channel={conversationChannel(row)} className="size-4" />
              <span className="truncate">{string(row, "inboxDisplay", conversationChannel(row))}</span>
            </span>
          </SelectItem>
        ))}
      </SelectContent>
    </Select>);
  // The sending mailbox, when the connection offers more than one. It takes the From row; switching
  // conversations then moves to a row of its own, labelled as the channel it is.
  const senderLabel = (id: string | null) => { const sender = composer.senders.find(item => item.id === id); return sender?.label || sender?.id || ""; };
  const mailboxSelect = composer.chooseSender && composer.senderId ? <Select value={composer.senderId} disabled={composer.busy || composer.replyState.disabled || composer.mode === "note"}
    onValueChange={(id: string) => composer.setSender(id)}>
    <SelectTrigger aria-label={t("crm.chat.sendFrom")} className={selectTriggerClass}>
      <SelectValue><span className="truncate">{senderLabel(composer.senderId)}</span></SelectValue>
    </SelectTrigger>
    <SelectContent>{composer.senders.map(sender => <SelectItem key={sender.id} value={sender.id}>{sender.label || sender.id}</SelectItem>)}</SelectContent>
  </Select> : null;
  return <div data-sender-account className={labels ? "flex min-w-0 max-w-full flex-col text-xs" : "flex min-w-0 max-w-full flex-wrap items-center gap-1 text-xs"}>
    <div className="flex min-w-0 max-w-full items-center gap-2">
      {labels && <span className="w-12 shrink-0 text-muted-foreground">{t("crm.chat.from")}</span>}
      {mailboxSelect ?? conversationSwitch(t("crm.chat.sendFrom"))}
    </div>
    {mailboxSelect && options.length > 1 && <div className="flex min-w-0 max-w-full items-center gap-2">
      {labels && <span className="w-12 shrink-0 text-muted-foreground">{t("crm.chat.channel")}</span>}
      {conversationSwitch(t("crm.chat.switchConversation"))}
    </div>}
  </div>;
}

/**
 * An app-supplied reply field; draft storage, delivery and all other fields stay with the composer.
 * `formatting` says the channel carries formatted text (email): the field may then report HTML with the text,
 * `onChange(text, html)`, which is sent as the email's HTML while `text` stays its plain part. `html` is the
 * draft's current HTML (null when plain, or after something else changed the text). `onChange(text)` alone
 * keeps the reply plain.
 */
export type CrmReplyInputProps = {
  value: string; html: string | null; formatting: boolean;
  onChange: (value: string, html?: string | null) => void; onSubmit: () => void;
  disabled: boolean; maxLength: number; label: string; placeholder: string;
  describedBy?: string; surface: "inline" | "window";
};

/**
 * The paperclip's place, for an app that draws its own (a menu of files and templates, say): `chooseFiles`
 * opens the composer's file picker (it does nothing while files can't go; `composer.attachState` says why).
 * `labelled` is the mobile actions panel's row, which shows a label beside the icon.
 */
export type CrmAttachProps = { composer: CrmComposerState; chooseFiles: () => void; surface: "inline" | "window"; labelled: boolean };

/**
 * The fields above the reply, as parts an app may arrange (fold them behind one line, as Gmail does): `header`
 * is the default first row (the sender controls, with the larger-window button inline), `sender` the sender
 * controls alone, `recipient` and `subject` the email rows (null elsewhere), `recipientNotice` the note shown
 * when the recipient changed. `expand` opens the larger window when it may open (inline only). `summary` is what
 * the rows hold, and `changed` whether the planner changed the recipient or subject.
 */
export type CrmComposerFieldParts = {
  surface: "inline" | "window"; mode: ComposerMode;
  header: ReactNode; sender: ReactNode; recipient: ReactNode | null; recipientNotice: ReactNode | null; subject: ReactNode | null;
  expand?: () => void;
  summary: { recipient: string | null; subject: string };
  changed: boolean;
};

/** Props shared by the composer's parts. */
export type CrmComposerPartProps = {
  conversation: CrmConversationState;
  composer: CrmComposerState;
  config?: Config;
};

/**
 * The composer's fields: recipient and subject (email), the reply or note editor, the attachment
 * tray, the channel's status, errors. `header` is drawn first (the sender controls, an expand button).
 */
export function CrmComposerFields({ conversation, composer, config, surface = "inline", compact = false, header, sender, expand, replyInputRef, onSubmit, renderReplyInput, renderFields }: CrmComposerPartProps & {
  surface?: "inline" | "window"; compact?: boolean; header?: ReactNode;
  /** The sender controls alone, for `renderFields` (default: `header`). */
  sender?: ReactNode;
  /** Opens the larger window, for `renderFields`. */
  expand?: () => void;
  /** Arranges the fields above the reply (see `CrmComposerFieldParts`); by default they are drawn in rows. */
  renderFields?: (parts: CrmComposerFieldParts) => ReactNode;
  replyInputRef?: Ref<HTMLTextAreaElement>;
  renderReplyInput?: (props: CrmReplyInputProps) => ReactNode;
  /** What Ctrl/Cmd+Enter does (default: send the draft). */
  onSubmit?: () => void;
}) {
  const subjectId = useId();
  const statusId = useId();
  const selected = conversation.conversation;
  if (!selected) return null;
  const inline = surface === "inline";
  const { delivery } = conversation;
  const { mode, replyState } = composer;
  const sendShortcut = (event: { key: string; metaKey: boolean; ctrlKey: boolean; defaultPrevented: boolean; nativeEvent?: { isComposing?: boolean }; preventDefault: () => void }) => {
    if (event.defaultPrevented || event.nativeEvent?.isComposing) return;
    if (event.key === "Enter" && (event.metaKey || event.ctrlKey)) { event.preventDefault(); submit(); }
  };
  const submit = onSubmit ?? (() => void composer.submit());
  const error = composer.error ?? conversation.error;
  const email = mode === "reply" && conversationChannel(selected) === "EMAIL";
  const recipient = email && !!delivery?.recipient ? <div className="mb-1 flex min-w-0 items-center gap-2 text-xs">
      <span className="w-12 shrink-0 text-muted-foreground">{t("crm.chat.to")}</span>
      {(delivery.recipients?.length ?? 0) > 1 ? <Select value={composer.recipientDraft ?? delivery.recipient} disabled={composer.busy || replyState.disabled}
        onValueChange={(value: string) => composer.setRecipientDraft(value === delivery.recipient ? null : value)}>
        <SelectTrigger aria-label={t("crm.chat.to")} className="h-9 w-auto max-w-full min-w-0 gap-2 border-0 bg-transparent px-1 text-xs shadow-none hover:bg-muted"><SelectValue /></SelectTrigger>
        <SelectContent>{delivery.recipients?.map(address => <SelectItem key={address} value={address}>{address}</SelectItem>)}</SelectContent>
      </Select> : <span className="flex h-9 min-w-0 items-center truncate px-1">{composer.recipientDraft ?? delivery.recipient}</span>}
    </div> : null;
  const recipientChanged = composer.recipientDraft !== null && composer.recipientDraft !== delivery?.recipient;
  const recipientNotice = mode === "reply" && recipientChanged ? <p className="mb-2 text-xs text-muted-foreground">{t("crm.chat.newRecipientThread")}</p> : null;
  const subject = email ? <div className="mb-2">
      <div className="flex min-w-0 items-center gap-2 border-b border-border/60 pb-1 transition-colors focus-within:border-primary">
        <label htmlFor={subjectId} className="w-12 shrink-0 text-xs text-muted-foreground">{t("crm.chat.subject")}</label>
        <Input id={subjectId} value={composer.subjectDraft ?? string(selected, "subject", "")}
          maxLength={998} disabled={composer.busy || replyState.disabled}
          className="h-9 min-w-0 flex-1 rounded-none border-0 bg-transparent px-1 py-0 text-sm shadow-none focus-visible:outline-none focus-visible:ring-0 focus-visible:ring-offset-0 focus-visible:shadow-none"
          onChange={(event: { target: { value: string } }) => composer.setSubjectDraft(event.target.value === string(selected, "subject", "") ? null : event.target.value)} />
      </div>
      {composer.subjectDraft !== null && <p className="mt-1 text-[10px] text-muted-foreground">{t("crm.chat.newSubjectThread")}</p>}
    </div> : null;
  return <>
    {renderFields ? renderFields({ surface, mode, header, sender: sender ?? header, recipient, recipientNotice, subject, expand,
      summary: { recipient: composer.recipientDraft ?? delivery?.recipient ?? null, subject: composer.subjectDraft ?? string(selected, "subject", "") },
      changed: composer.subjectDraft !== null || recipientChanged })
      : <>{header}{recipient}{recipientNotice}{subject}</>}
    <div hidden={mode !== "note"} className={!inline && mode === "note" ? "flex min-h-0 flex-1 flex-col" : undefined}>
      <NoteInput key={composer.draftKey ?? ""} draftKey={composer.draftKey ?? undefined} value={composer.noteDraft} autoGrow={inline} fill={!inline}
        disabled={composer.busy || !action(config, "note")} onChange={(text, body) => composer.setNote(text, body)}
        onSubmit={submit} />
    </div>
    {mode === "reply" && !replyState.disabled && (renderReplyInput ? renderReplyInput({
      value: composer.draft, html: composer.draftHtml, formatting: composer.formatting,
      onChange: (text: string, html?: string | null) => html === undefined || !composer.formatting ? composer.setDraft(text) : composer.setFormattedDraft(text, html),
      onSubmit: submit,
      disabled: composer.busy || replyState.disabled, maxLength: delivery?.maxTextLength ?? 4096,
      label: t("crm.composer.replyLabel"), placeholder: replyState.placeholder,
      describedBy: replyState.message ? statusId : undefined, surface,
    }) : <Textarea
      ref={replyInputRef}
      data-composer-reply=""
      value={composer.draft}
      disabled={replyState.disabled}
      onChange={(event: { target: { value: string } }) => composer.setDraft(event.target.value)}
      onKeyDown={sendShortcut}
      placeholder={replyState.placeholder}
      aria-describedby={replyState.message ? statusId : undefined}
      maxLength={delivery?.maxTextLength ?? 4096}
      aria-label={t("crm.composer.replyLabel")}
      className={`${inline ? "min-h-16" : "min-h-48 flex-1"} resize-none border-0 bg-transparent dark:bg-transparent px-2 shadow-none focus-visible:outline-none focus-visible:ring-0 focus-visible:ring-offset-0 focus-visible:shadow-none`}
    />)}
    {/* What is about to go with the reply. It sits under the text because that is the order
        it will arrive in, and each chip can be taken back out before sending. */}
    {mode === "reply" && (composer.pending.length > 0 || composer.uploading > 0) && <div className="mt-1 flex flex-wrap gap-1.5 px-2">
      {composer.pending.map(file => (
        <span key={file.url} className="inline-flex max-w-full items-center gap-1.5 rounded-pill bg-muted px-2 py-1 text-[11px]">
          <Paperclip className="size-3 shrink-0" aria-hidden="true" />
          <span className="min-w-0 truncate font-medium">{file.filename}</span>
          {file.size > 0 && <span className="shrink-0 tabular-nums text-muted-foreground">{fileSize(file.size)}</span>}
          <Button variant="ghost" type="button" aria-label={t("crm.chat.removeFile", { name: file.filename })} disabled={composer.busy}
            className="h-auto p-0 text-inherit" onClick={() => composer.removeFile(file.url)}>
            <X className="size-3" />
          </Button>
        </span>
      ))}
      {composer.uploading > 0 && <span role="status" className="inline-flex items-center gap-1.5 rounded-pill bg-muted px-2 py-1 text-[11px] text-muted-foreground">
        {t("crm.chat.uploading", { count: composer.uploading })}
      </span>}
    </div>}
    {mode === "reply" && replyState.message && <div id={statusId} role="status" className={`flex items-center gap-2 rounded-field bg-muted/50 text-xs text-muted-foreground ${compact && inline ? "px-2 py-1" : "px-3 py-2"}`}>
      <span className="flex-1">{replyState.message}</span>
      {replyState.retry && <Button variant="ghost" aria-label={t("crm.chat.checkAgain")} className={compact && inline ? "size-10 shrink-0 p-0" : undefined} onClick={() => void conversation.reload()}>{compact && inline ? <RefreshCw className="size-4" /> : t("crm.chat.checkAgain")}</Button>}
    </div>}
    {error && <p role="alert" className="mt-2 text-xs text-destructive">{error}</p>}
  </>;
}

/** The "attach files" button, disabled with the channel's reason when files cannot go. */
export function AttachButton({ composer, onChoose, labelled = false }: { composer: CrmComposerState; onChoose: () => void; labelled?: boolean }) {
  if (!composer.showAttachments) return null;
  const { attachState } = composer;
  return <span className="inline-flex" title={attachState.reason}>
    <Button type="button" variant="ghost" size={labelled ? "toolbar" : undefined} aria-label={labelled ? undefined : t("crm.chat.attach")} disabled={attachState.disabled}
      aria-description={attachState.disabled ? attachState.reason : undefined} onClick={onChoose}>
      <Paperclip className="size-4" />{labelled && t("crm.chat.attach")}
    </Button>
  </span>;
}

/** The overlay shown while files are dragged over the chat. */
export function FileDropOverlay({ composer }: { composer: CrmComposerState }) {
  if (!composer.fileDragActive) return null;
  return <div className="pointer-events-none absolute inset-2 z-50 flex items-center justify-center rounded-panel border-2 border-dashed border-primary bg-background/95 p-6 text-center" role="status">
    <div className="flex flex-col items-center gap-3">
      <Paperclip className="size-8 text-primary" aria-hidden="true" />
      <p className="text-sm font-medium">{composer.fileDropReason || t("crm.chat.dropFiles")}</p>
      {!composer.fileDropReason && <p className="text-xs text-muted-foreground">{t("crm.chat.dropFilesHint")}</p>}
    </div>
  </div>;
}

/** Toolbar arrangements of the composer, per screen size. */
export type CrmComposerLayouts = { composer?: WorkspaceToolbarLayout; mobileComposer?: WorkspaceToolbarLayout };

/** Props of {@link CrmComposer}. */
export type CrmComposerProps = CrmComposerPartProps & {
  compact?: boolean;
  layouts?: CrmComposerLayouts;
  /** The conversation's extension context: the composer's outlets (`crm.chat.composer`) get it. */
  extensionContext: ExtensionContext;
  /** The contact's conversations a reply may switch to, and how to switch. */
  siblings?: readonly EntityRecord[];
  onSwitch?: (conversationId: string) => void;
  /** The window title's contact name. */
  contactName: string;
  /** Offer the "Open in a larger window" button (default true). */
  expandable?: boolean;
  /** Replace only the reply editor in both the inline composer and its larger window. */
  renderReplyInput?: (props: CrmReplyInputProps) => ReactNode;
  /** Replace the paperclip wherever the composer draws it (toolbar, mobile actions, larger window). */
  renderAttach?: (props: CrmAttachProps) => ReactNode;
  /** Arrange the fields above the reply, in both the inline composer and its larger window (`CrmComposerFieldParts`). */
  renderFields?: (parts: CrmComposerFieldParts) => ReactNode;
  /** The id that keeps the composer window's place (default: one per conversation in an inbox, else one per workspace). */
  windowId?: string;
  /**
   * Draw only the larger window, not the inline composer. The inbox uses it for the windows of conversations it
   * is not showing (see `composerWindows`); an app's own `Composer` should pass it through to `CrmComposer`.
   */
  windowOnly?: boolean;
};

/**
 * The composer as the inbox draws it: fields plus a toolbar (mode, attach, the app's composer
 * outlet, send), with an "Open in a larger window" button that moves the same draft into a docked,
 * non-modal window.
 */
export function CrmComposer({ conversation, composer, config, compact = false, layouts, extensionContext, siblings, onSwitch, contactName, expandable = true, windowId, windowOnly = false, renderReplyInput, renderAttach, renderFields }: CrmComposerProps) {
  const selected = conversation.conversation;
  // In an inbox the window belongs to the conversation (composerWindows), so it outlives this composer when
  // another chat is chosen; elsewhere it is this composer's own.
  const host = useComposerWindowHost();
  const windows = useComposerWindows();
  const key = host && conversation.id ? composerDrafts.key(conversation.workspace, conversation.id) : null;
  const [ownExpanded, setOwnExpanded] = useState(false);
  const expanded = key ? windows.isOpen(key) : ownExpanded;
  const setExpanded = (open: boolean) => {
    if (!key) { setOwnExpanded(open); return; }
    if (!open) windows.close(key);
    else if (selected) windows.open({ key, workspace: host!.workspace, conversation: selected, contactName });
  };
  const rootRef = useRef<HTMLDivElement | null>(null);
  const windowRef = useRef<HTMLDivElement | null>(null);
  const replyInputRef = useRef<HTMLTextAreaElement | null>(null);
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  useAutoGrow(replyInputRef, composer.draft, !expanded);
  // Writing moves with the window: into it when it opens, back to the inline field when it closes
  // (the expand button that opened it is gone by then, so the window cannot hand focus back itself).
  // A window already open when this composer mounts (taken over from another chat's view) keeps the focus where it is.
  const wasExpanded = useRef(expanded);
  useEffect(() => {
    if (wasExpanded.current === expanded) return;
    wasExpanded.current = expanded;
    const frame = requestAnimationFrame(() => focusComposerField(expanded ? windowRef.current : rootRef.current, composer.mode));
    return () => cancelAnimationFrame(frame);
  }, [expanded]);
  if (!selected) return null;
  const { mode, busy, sendAction } = composer;
  const send = async () => { if (await composer.submit()) setExpanded(false); };
  const chooseAttachment = () => { if (!composer.attachState.disabled) fileInputRef.current?.click(); };
  const sendShortcut = (event: { key: string; metaKey: boolean; ctrlKey: boolean; defaultPrevented: boolean; nativeEvent?: { isComposing?: boolean }; preventDefault: () => void }) => {
    if (event.defaultPrevented || event.nativeEvent?.isComposing) return;
    if (event.key === "Enter" && (event.metaKey || event.ctrlKey)) { event.preventDefault(); void send(); }
  };
  const composerContext = { ...extensionContext, insertDraft: mode === "reply" ? composer.insertDraft : undefined };
  const attach = (surface: "inline" | "window", labelled = false) => renderAttach
    ? renderAttach({ composer, chooseFiles: chooseAttachment, surface, labelled })
    : <AttachButton composer={composer} onChoose={chooseAttachment} labelled={labelled} />;
  const expand = expandable && composer.canExpand ? () => setExpanded(true) : undefined;
  // Switching to another of the contact's chats from the window takes the window along.
  const switchInWindow = onSwitch && ((conversationId: string) => {
    const target = siblings?.find(row => String(row.id) === conversationId);
    if (key && target) {
      windows.close(key);
      windows.open({ key: composerDrafts.key(conversation.workspace, conversationId), workspace: host!.workspace, conversation: target, contactName });
    }
    onSwitch(conversationId);
  });
  const sending = <SenderControls conversation={conversation} composer={composer} siblings={siblings} onSwitch={onSwitch} labels={!compact} />;
  const modeSwitch = <Segmented value={mode} onChange={(value: ComposerMode) => { if (!busy) composer.setMode(value); }} options={composer.modeOptions} />;
  const insertInvitation = (text: string) => { composer.setMode("reply"); composer.setDraft(previous => previous ? `${previous}\n\n${text}` : text); };
  const inlineHeader = <div className={`mb-2 flex items-center justify-between ${compact ? "gap-1" : "gap-3"}`}>
    {compact && layouts?.mobileComposer?.center?.includes("channel") ? modeSwitch : sending}
    <span className="flex shrink-0 items-center gap-1">
      <span hidden={compact && mode === "reply"} title={mode === "reply" ? conversation.delivery?.label : undefined} className="text-[10px] text-muted-foreground">
        {mode === "note" ? t("crm.composer.noteHint") : ""}
      </span>
      {expandable && composer.canExpand && <Button type="button" variant="ghost" size="toolbar" className="size-8 shrink-0 p-0" aria-haspopup="dialog"
        aria-label={t("crm.composer.expand")} title={t("crm.composer.expand")} onClick={() => setExpanded(true)}>
        <Maximize2 className="size-4" aria-hidden="true" />
      </Button>}
    </span>
  </div>;
  // The large composer is a non-modal window: docked in the corner by default, so the rest of the
  // screen (other chats, the contact, the timeline) stays usable while writing. It can float, be
  // resized, minimised to its title bar or maximised; it edits the same draft as the inline field.
  const window = expanded ? <FloatingWindow id={windowId ?? (key ? `crm-composer:${key}` : `crm-composer:${conversation.workspace ?? ""}`)}
    open onOpenChange={(open: boolean) => { if (!open) setExpanded(false); }}
    initialState="docked" persist="crm.composer" width={620} height={580} minWidth={380} minHeight={320}
    icon={<PenLine className="size-4" aria-hidden="true" />}
    title={mode === "note" ? t("crm.composer.noteTitle", { name: contactName }) : t("crm.composer.replyTitle", { name: contactName })}
    bodyClassName={mode === "note" ? "bg-amber-400/5" : undefined}
    footer={<div className="flex w-full min-w-0 flex-wrap items-center gap-2">
      {composer.modeOptions.length > 0 && modeSwitch}
      {attach("window")}
      <ExtensionSlot name="crm.chat.composer" context={composerContext} className="flex items-center gap-2" />
      <span className="flex-1" />
      {sendAction && <Button type="button" disabled={composer.cannotSend} onClick={() => void send()}>
        {busy ? t("crm.composer.sending") : sendAction.label}
      </Button>}
    </div>}>
    <div ref={windowRef} data-crm-composer-window="" onKeyDown={sendShortcut} className="relative flex min-h-0 flex-1 flex-col">
      <CrmComposerFields conversation={conversation} composer={composer} config={config} surface="window" renderReplyInput={renderReplyInput} renderFields={renderFields} onSubmit={() => void send()}
        header={<div className="mb-2 flex min-w-0 flex-col gap-1">
          <SenderControls conversation={conversation} composer={composer} siblings={siblings} onSwitch={switchInWindow} labels />
          {mode === "note" && <p className="text-xs text-muted-foreground">{t("crm.composer.noteHint")}</p>}
        </div>} />
      <FileDropOverlay composer={composer} />
    </div>
  </FloatingWindow> : null;
  const fileInput = <input ref={fileInputRef} type="file" multiple className="hidden"
    onChange={(event: { target: { files: FileList | null; value: string } }) => { void composer.attach(event.target.files); event.target.value = ""; }} />;
  if (windowOnly) return <>{fileInput}{window}</>;
  return <>
    {fileInput}
    <div ref={rootRef} data-chat-composer className="crm-chat-composer shrink-0 rounded-panel border border-border">
      {expanded ? <p className={`${compact ? "p-2" : "p-3"} text-xs text-muted-foreground`}>{t("crm.composer.inWindow")}</p> : <div className={`${compact ? "p-2" : "p-3"} ${mode === "note" ? "bg-amber-400/5" : ""}`}>
        <CrmComposerFields conversation={conversation} composer={composer} config={config} surface="inline" compact={compact} renderReplyInput={renderReplyInput} onSubmit={() => void send()}
          header={inlineHeader} sender={sending} expand={expand} renderFields={renderFields} replyInputRef={replyInputRef} />
        <WorkspaceToolbar compact={compact} className="mt-2"
          layout={{ start: ["mode", "attach"], end: ["send"], secondary: ["extensions"], ...layouts?.composer }}
          compactLayout={layouts?.mobileComposer}
          items={[
            { id: "channel", content: sending },
            { id: "tools", content: <ResponsiveActionPanel presentation="actions" label={t("crm.chat.actions")} align="start" trigger={<Button variant="ghost" className="size-10 shrink-0 p-0" aria-label={t("crm.chat.actions")}><Plus className="size-5" /></Button>}>
                {action(config, "logActivity") && <ScheduleCall customerId={String(selected.customer)} conversationId={String(selected.id)} onSaved={conversation.reload} onInvitation={insertInvitation} />}
                {action(config, "logActivity") && <LogActivity customerId={String(selected.customer)} conversationId={String(selected.id)} onSaved={conversation.reload} />}
                {attach("inline", true)}
                <ExtensionSlot name="crm.chat.header" context={extensionContext} className="flex flex-col gap-2" />
              </ResponsiveActionPanel> },
            { id: "mode", content: modeSwitch },
            { id: "attach", content: attach("inline") },
            { id: "extensions", content: <ExtensionSlot name="crm.chat.composer" context={composerContext} className="flex items-center gap-2" /> },
            { id: "send", content: sendAction && <Button aria-label={busy ? t("crm.composer.sending") : sendAction.label} className={compact ? "size-10 shrink-0 p-0" : undefined} disabled={composer.cannotSend} onClick={() => void send()}>
              {compact ? <Send className="size-4" /> : busy ? t("crm.composer.sending") : sendAction.label}
            </Button> },
          ]} />
      </div>}
    </div>
    {window}
  </>;
}
