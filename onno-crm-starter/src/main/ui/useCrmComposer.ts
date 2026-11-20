import { text as t, type DraftFile } from "@onno/ui";
import { useEffect, useRef, useState, useSyncExternalStore, type DragEvent } from "react";
import { composerState } from "./composerState";
import { composerDrafts, installDraftUnloadGuard, EMPTY_DRAFT, type ComposerDraft, type ComposerMode } from "./composerDrafts";
import { action, type Config } from "./CrmWorkspace";
import { checkedJson, conversationChannel, fileSize, workspaceCommand, type CrmAttachment, type CrmMessage } from "./crmModel";
import type { CrmConversationState } from "./useCrmConversation";

/**
 * A conversation's composer, without its look: the draft (kept per conversation, across chats, tabs
 * and reloads), attachments, the sending mailbox, and send/retry. The inbox's composer, the docked
 * window and an app's own composer all drive the same draft through it.
 */
export function useCrmComposer(conversation: CrmConversationState, config: Config | undefined) {
  const { id, workspace, delivery } = conversation;
  const row = conversation.conversation;
  // The draft belongs to the conversation, not to this mount (composerDrafts.ts): switching chats,
  // contacts, inbox modes or tabs and coming back finds the text, subject, recipient, sending
  // mailbox, mode and attached files as they were left. Only a send or an emptied field clears it.
  useSyncExternalStore(composerDrafts.subscribe, composerDrafts.version, composerDrafts.version);
  useEffect(() => { installDraftUnloadGuard(); }, []);
  const draftKey = id ? composerDrafts.key(workspace, id) : null;
  const defaultMode: ComposerMode = !config || action(config, "reply") ? "reply" : "note";
  const composed: ComposerDraft = (draftKey && composerDrafts.read(draftKey)) || { ...EMPTY_DRAFT, mode: defaultMode };
  const { mode, body: draft, html: draftHtml, subject: subjectDraft, recipient: recipientDraft, note: noteDraft, noteBody } = composed;
  // Files already uploaded and waiting to go with the next reply.
  const pending: CrmAttachment[] = draftKey ? composerDrafts.files(draftKey) : [];
  const updateDraft = (change: (current: ComposerDraft) => ComposerDraft, key: string | null = draftKey) => {
    if (!key) return;
    composerDrafts.write(key, change(composerDrafts.read(key) ?? { ...EMPTY_DRAFT, mode: key === draftKey ? mode : defaultMode }));
  };
  const setMode = (next: ComposerMode) => updateDraft(current => ({ ...current, mode: next }));
  /** Sets the reply's text; any formatting it had is dropped (the text no longer matches it). */
  const setDraft = (next: string | ((previous: string) => string)) =>
    updateDraft(current => {
      const body = typeof next === "function" ? next(current.body) : next;
      return { ...current, body, html: body === current.body ? current.html : null };
    });
  /** Sets the reply's text and its formatted HTML together (a formatting reply editor); `null` HTML is plain text. */
  const setFormattedDraft = (body: string, html: string | null) => updateDraft(current => ({ ...current, body, html: html || null }));
  const setSubjectDraft = (subject: string | null) => updateDraft(current => ({ ...current, subject }));
  const setRecipientDraft = (recipient: string | null) => updateDraft(current => ({ ...current, recipient }));
  const setNote = (text: string, body: string) => updateDraft(current => ({ ...current, note: text, noteBody: body }));
  const setPending = (update: CrmAttachment[] | ((files: CrmAttachment[]) => CrmAttachment[]), key: string | null = draftKey) => {
    if (key) composerDrafts.setFiles(key, update);
  };
  const removeFile = (url: string) => setPending(current => current.filter(item => item.url !== url));
  const [uploading, setUploading] = useState(0);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const idRef = useRef(id);
  idRef.current = id;
  useEffect(() => { setError(null); }, [id]);

  const replyState = composerState({ canReply: !!action(config, "reply") && !!action(config, "sendReply"), busy,
    loaded: conversation.deliveryLoaded, failed: conversation.deliveryFailed, delivery });
  // The clip follows the channel: a full tray, a read-only channel and a channel that simply does
  // not carry files are three different answers, and the button's title gives the right one.
  const attachState = (() => {
    if (replyState.disabled) return { disabled: true, reason: replyState.message || "Replies are unavailable" };
    const limit = delivery?.maxAttachments ?? 0;
    if (uploading > 0) return { disabled: true, reason: t("crm.chat.uploading", { count: uploading }) };
    if (limit <= 0) return { disabled: true, reason: delivery?.attachmentReason || t("crm.chat.attachUnavailable") };
    if (pending.length >= limit) return { disabled: true, reason: t("crm.chat.attachLimit", { count: limit }) };
    return { disabled: false, reason: t("crm.chat.attach") };
  })();
  // Pin the recipient once writing starts, but only with this conversation's own connection loaded.
  useEffect(() => {
    if (!conversation.deliveryLoaded || recipientDraft !== null) return;
    if (mode === "reply" && (draft.trim() || pending.length || subjectDraft !== null) && delivery?.recipient)
      setRecipientDraft(delivery.recipient);
  }, [mode, draft, pending.length, subjectDraft, delivery?.recipient, recipientDraft, conversation.deliveryLoaded]);
  // A mode the workspace does not allow falls back to one it does.
  useEffect(() => {
    if (config && !action(config, mode)) { const next = config.actions.find(a => a.visible && ["reply", "note"].includes(a.key)); if (next) setMode(next.key as ComposerMode); }
  }, [config, mode]);

  // The mailboxes a reply may leave from. A choice only exists with two or more; a stored choice the
  // connection no longer offers falls back to its default.
  const senders = (delivery?.senders ?? []).filter(sender => sender && typeof sender.id === "string" && sender.id);
  const chooseSender = senders.length > 1;
  const defaultSender = senders.find(sender => sender.id === delivery?.sender)?.id ?? senders[0]?.id ?? null;
  const senderId = chooseSender ? senders.find(sender => sender.id === composed.from)?.id ?? defaultSender : null;
  const setSender = (senderKey: string) => updateDraft(current => ({ ...current, from: senderKey === defaultSender ? null : senderKey }));

  /**
   * Upload what the agent picked, then keep it in the tray until the reply is sent. Uploading on
   * pick rather than on send means the paperclip and the Send button never race, and a file that
   * the server refuses says so while there is still a composer to say it in.
   */
  const attach = async (files: FileList | null) => {
    if (!files?.length || attachState.disabled || !draftKey) return;
    // Files belong to the draft they were picked for, even if the reader moves on while they upload.
    const conversationId = id;
    const key = draftKey;
    const here = () => idRef.current === conversationId;
    let maxFileSize: number;
    try {
      const limits = await fetch("/api/media/limits", { credentials: "same-origin" });
      const policy = await checkedJson<{ maxFileSize: number }>(limits, t("crm.chat.uploadLimitsUnavailable"));
      if (typeof policy.maxFileSize !== "number" || policy.maxFileSize <= 0) { setError(t("crm.chat.uploadLimitsUnavailable")); return; }
      maxFileSize = policy.maxFileSize;
    } catch {
      if (here()) setError(t("crm.chat.uploadLimitsUnavailable"));
      return;
    }
    const room = (delivery?.maxAttachments ?? 0) - composerDrafts.files(key).length;
    const chosen = Array.from(files).slice(0, Math.max(0, room));
    if (here()) setError(chosen.length < files.length ? t("crm.chat.attachTooMany", { count: delivery?.maxAttachments ?? 0 }) : null);
    for (const file of chosen) {
      if (file.size > maxFileSize) {
        if (here()) setError(t("crm.chat.uploadTooLarge", { name: file.name, limit: fileSize(maxFileSize) }));
        continue;
      }
      setUploading(count => count + 1);
      try {
        const form = new FormData();
        form.append("file", file);
        const token = document.cookie.split(";").map(part => part.trim()).find(part => part.startsWith("XSRF-TOKEN="))?.slice(11);
        const response = await fetch("/api/media", { method: "POST", credentials: "same-origin",
          headers: token ? { "X-XSRF-TOKEN": decodeURIComponent(token) } : undefined, body: form });
        if (!response.ok) {
          const failure = await response.json().catch(() => ({}));
          if (here()) setError(failure.message || failure.detail || t("crm.chat.uploadFailedFile", { name: file.name }));
          continue;
        }
        const stored = await response.json();
        setPending(current => current.some(item => item.url === stored.url) ? current : [...current, {
          url: stored.url, filename: stored.filename || file.name,
          contentType: stored.contentType || file.type || "application/octet-stream",
          size: stored.size ?? file.size,
          image: /^image\/(png|jpeg|gif|webp|avif)$/.test(stored.contentType || file.type || ""),
        }], key);
      } catch (uploadError) {
        if (here()) setError(uploadError instanceof Error ? uploadError.message : t("crm.chat.uploadFailed"));
      } finally {
        setUploading(count => count - 1);
      }
    }
  };

  const sendAction = action(config, mode === "reply" ? "sendReply" : "sendNote");
  const hasText = !!(mode === "note" ? noteDraft : draft).trim();
  const cannotSend = !action(config, mode) || busy || uploading > 0 || (!hasText && !(mode === "reply" && pending.length > 0)) || (mode === "reply" && replyState.disabled);

  /** Sends the current draft (a reply, or a note); resolves true once it went out. */
  const submit = async (): Promise<boolean> => {
    const hasFiles = mode === "reply" && pending.length > 0;
    if (!action(config, mode) || !sendAction || !row || !id || !draftKey
      || (!hasText && !hasFiles) || busy || uploading > 0 || (mode === "reply" && replyState.disabled)) return false;
    // What goes out is this conversation's draft as it stands now; only that part is cleared after.
    const sentKey = draftKey, sentMode = mode;
    setBusy(true);
    setError(null);
    try {
      if (mode === "note") {
        await workspaceCommand(workspace, workspace ? `/api/crm/inbox-workspaces/${workspace}/conversations/${id}/comments` : `/api/comments/catalogs/crm_conversations/${id}`, {
          body: noteBody, parentId: null,
        });
      } else {
        const email = conversationChannel(row) === "EMAIL";
        await workspaceCommand(workspace, `/api/crm/conversations/${id}/messages`, {
          body: draft,
          // The formatted version of the same text; only email carries it, and older backends ignore it.
          ...(email && draftHtml ? { html: draftHtml } : {}),
          recipient: email ? recipientDraft ?? delivery?.recipient : undefined,
          subject: email ? subjectDraft : undefined,
          attachments: pending.map(file => file.url),
          // Only offered with a real choice, so older backends never see the field.
          ...(chooseSender && senderId ? { from: senderId } : {}),
        });
      }
      composerDrafts.clearSent(sentKey, sentMode);
      await conversation.reload();
      return true;
    } catch (sendError) {
      setError(sendError instanceof Error ? sendError.message : t("crm.error.send"));
      return false;
    } finally {
      setBusy(false);
    }
  };

  /** Sends a failed message again. */
  const retry = async (message: Pick<CrmMessage, "id" | "conversationId">) => {
    if (!action(config, "retry") || !id || busy || !delivery?.connected) return;
    setBusy(true);
    setError(null);
    try {
      await workspaceCommand(workspace, `/api/crm/conversations/${message.conversationId || id}/messages/${message.id}/retry`);
      await conversation.reload();
    } catch (retryError) {
      setError(retryError instanceof Error ? retryError.message : t("crm.error.retry"));
    } finally { setBusy(false); }
  };

  /**
   * Adds text to the reply (a template, a quoted date) and files already stored through `/api/media`
   * (a generated PDF) to its tray; it is never sent by this call. Files the channel can't carry, or
   * more than its limit, are refused with the composer's own reason, before anything changes.
   */
  const insertDraft = (text: string, files: readonly DraftFile[] = []) => {
    if (replyState.disabled) throw new Error(t("crm.error.replyUnavailable"));
    const next = !text.trim() ? draft : draft.trim() ? `${draft}\n\n${text}` : text;
    if (next.length > (delivery?.maxTextLength ?? 4096)) throw new Error(t("crm.error.replyTooLong"));
    const added = files.filter(file => file?.url && !pending.some(item => item.url === file.url));
    if (added.length) {
      const limit = delivery?.maxAttachments ?? 0;
      if (limit <= 0) throw new Error(delivery?.attachmentReason || t("crm.chat.attachUnavailable"));
      if (pending.length + added.length > limit) throw new Error(t("crm.chat.attachLimit", { count: limit }));
    }
    setMode("reply");
    if (next !== draft) setDraft(next);
    if (added.length) setPending(current => [...current, ...added.map(file => {
      const contentType = file.contentType || "application/octet-stream";
      return { url: file.url, filename: file.filename, contentType, size: file.size ?? 0, image: /^image\/(png|jpeg|gif|webp|avif)$/.test(contentType) };
    })]);
  };

  // Files dragged onto the chat: accepted for a reply that can carry them, explained otherwise.
  const [fileDragActive, setFileDragActive] = useState(false);
  const fileDragDepth = useRef(0);
  useEffect(() => { fileDragDepth.current = 0; setFileDragActive(false); }, [id, mode]);
  const fileDropReason = mode !== "reply" ? t("crm.chat.dropReplyOnly") : attachState.disabled ? attachState.reason : null;
  const isFileDrag = (event: DragEvent) => Array.from(event.dataTransfer.types).includes("Files");
  const clearFileDrag = () => { fileDragDepth.current = 0; setFileDragActive(false); };
  const dropZone = {
    onDragEnter: (event: DragEvent) => { if (!isFileDrag(event)) return; event.preventDefault(); fileDragDepth.current += 1; setFileDragActive(true); },
    onDragOver: (event: DragEvent) => { if (!isFileDrag(event)) return; event.preventDefault(); event.dataTransfer.dropEffect = fileDropReason ? "none" : "copy"; },
    onDragLeave: (event: DragEvent) => {
      if (!isFileDrag(event)) return;
      event.preventDefault();
      fileDragDepth.current = Math.max(0, fileDragDepth.current - 1);
      if (!fileDragDepth.current) setFileDragActive(false);
    },
    onDrop: (event: DragEvent) => {
      if (!isFileDrag(event)) return;
      event.preventDefault(); event.stopPropagation(); clearFileDrag();
      if (fileDropReason) { setError(fileDropReason); return; }
      void attach(event.dataTransfer.files);
    },
    onDragEnd: clearFileDrag,
  };

  const modeOptions = (config?.actions ?? []).filter(a => a.visible && ["reply", "note"].includes(a.key)).map(a => ({ value: a.key as ComposerMode, label: a.label }));
  return {
    draftKey, mode, setMode, modeOptions,
    draft, setDraft, draftHtml, setFormattedDraft,
    /** Whether the reply may be formatted: email carries HTML, the other channels plain text only. */
    formatting: conversationChannel(row) === "EMAIL",
    subjectDraft, setSubjectDraft, recipientDraft, setRecipientDraft,
    noteDraft, noteBody, setNote, updateDraft,
    /** Anything typed or chosen that a switch of conversation would lose sight of. */
    hasDraft: !!(draft || noteDraft) || subjectDraft !== null || (recipientDraft !== null && recipientDraft !== delivery?.recipient),
    pending, removeFile, uploading, attach, attachState,
    showAttachments: mode === "reply" && !!action(config, "sendReply"),
    senders, chooseSender, senderId, defaultSender, setSender,
    replyState, sendAction, cannotSend,
    canExpand: mode === "note" ? !!action(config, "note") : !replyState.disabled,
    busy, error, setError,
    submit, retry, insertDraft,
    fileDragActive, fileDropReason, dropZone,
  };
}

export type CrmComposerState = ReturnType<typeof useCrmComposer>;
