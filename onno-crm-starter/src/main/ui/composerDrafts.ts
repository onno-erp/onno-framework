/**
 * Unsent composer work, kept per conversation so that switching chats, contacts, inbox modes or tabs
 * never throws away what someone was writing.
 *
 * The text parts (reply, edited subject, chosen recipient, chosen sending mailbox, reply-or-note mode
 * and the internal note) live in memory for the page session and in localStorage across reloads,
 * keyed by inbox workspace + conversation. Storage is best effort: every access is guarded, an entry
 * over {@link MAX_DRAFT_CHARS} stays in memory only, entries older than {@link DRAFT_TTL_MS} are
 * dropped, and at most {@link MAX_STORED_DRAFTS} are kept. Files already uploaded for the next reply
 * stay in memory only: a reload cannot show their upload again, so they are not worth persisting.
 *
 * A draft leaves only when it is sent ({@link DraftStore.clearSent}) or emptied by its author.
 */
export type ComposerMode = "reply" | "note";
export type DraftFile = { url: string; filename: string; contentType: string; size: number; image: boolean };
export type ComposerDraft = {
  mode: ComposerMode;
  /** The reply as typed. */
  body: string;
  /**
   * The same reply as formatted HTML, when a reply editor that formats (`CrmReplyInputProps.formatting`) wrote it;
   * `null` for plain text. It belongs to `body`: any change of `body` without new HTML drops it.
   */
  html: string | null;
  /** An edited email subject; `null` keeps the conversation's own. */
  subject: string | null;
  /** The email recipient the reply is pinned to; `null` follows the connection's default. */
  recipient: string | null;
  /** The chosen sending mailbox (a connection `senders` id); `null` follows the connection's default. */
  from: string | null;
  /** The internal note as typed, and the same note with its references serialised for sending. */
  note: string;
  noteBody: string;
};

export const EMPTY_DRAFT: ComposerDraft = Object.freeze({ mode: "reply", body: "", html: null, subject: null, recipient: null, from: null, note: "", noteBody: "" });
const STORAGE_PREFIX = "onno.crm.draft.v1|";
/** Drafts untouched for this long are dropped from storage. */
export const DRAFT_TTL_MS = 30 * 24 * 60 * 60 * 1000;
/** At most this many drafts are kept in storage; the oldest go first. */
export const MAX_STORED_DRAFTS = 100;
/** A serialised draft longer than this is kept for the session only. */
export const MAX_DRAFT_CHARS = 64_000;

/** Typed text worth keeping (and worth a warning before the page unloads). */
export function hasDraftText(draft: ComposerDraft | null | undefined): boolean {
  return !!draft && (!!draft.body.trim() || !!draft.note.trim() || draft.subject !== null);
}

function isBlank(draft: ComposerDraft): boolean {
  return !hasDraftText(draft) && draft.mode === "reply" && draft.from === null && draft.recipient === null;
}

const nullableString = (value: unknown) => typeof value === "string" ? value : null;
function normalise(value: unknown): ComposerDraft | null {
  if (!value || typeof value !== "object") return null;
  const raw = value as Record<string, unknown>;
  return {
    mode: raw.mode === "note" ? "note" : "reply",
    body: typeof raw.body === "string" ? raw.body : "",
    html: nullableString(raw.html),
    subject: nullableString(raw.subject),
    recipient: nullableString(raw.recipient),
    from: nullableString(raw.from),
    note: typeof raw.note === "string" ? raw.note : "",
    noteBody: typeof raw.noteBody === "string" ? raw.noteBody : typeof raw.note === "string" ? raw.note : "",
  };
}

type Entry = { draft: ComposerDraft | null; files: DraftFile[]; touched: boolean };
export type DraftStore = ReturnType<typeof createDraftStore>;

export function createDraftStore({ storage = defaultStorage, now = Date.now, debounceMs = 300 }: {
  storage?: () => Storage | null; now?: () => number; debounceMs?: number;
} = {}) {
  const memory = new Map<string, Entry>();
  const listeners = new Set<() => void>();
  const dirty = new Set<string>();
  let version = 0;
  let timer: ReturnType<typeof setTimeout> | null = null;
  let pruned = false;

  const emit = () => { version++; listeners.forEach(listener => listener()); };
  const guarded = <T,>(work: (store: Storage) => T): T | undefined => {
    try {
      const store = storage();
      return store ? work(store) : undefined;
    } catch {
      return undefined;
    }
  };

  /** Drops expired, unreadable and surplus entries; `keep` lowers the cap when storage is full. */
  const prune = (keep = MAX_STORED_DRAFTS) => guarded(store => {
    const found: { key: string; savedAt: number }[] = [];
    const stale: string[] = [];
    for (let index = 0; index < store.length; index++) {
      const key = store.key(index);
      if (!key?.startsWith(STORAGE_PREFIX)) continue;
      try {
        const savedAt = Number(JSON.parse(store.getItem(key) ?? "null")?.savedAt);
        if (Number.isFinite(savedAt) && now() - savedAt <= DRAFT_TTL_MS) found.push({ key, savedAt });
        else stale.push(key);
      } catch {
        stale.push(key);
      }
    }
    found.sort((left, right) => right.savedAt - left.savedAt);
    for (const key of [...stale, ...found.slice(Math.max(0, keep)).map(entry => entry.key)]) store.removeItem(key);
  });
  const prepare = () => { if (!pruned) { pruned = true; prune(); } };

  const loadStored = (key: string): ComposerDraft | null => guarded(store => {
    prepare();
    const raw = store.getItem(STORAGE_PREFIX + key);
    if (!raw) return null;
    try {
      const parsed = JSON.parse(raw);
      if (!Number.isFinite(Number(parsed?.savedAt)) || now() - Number(parsed.savedAt) > DRAFT_TTL_MS) {
        store.removeItem(STORAGE_PREFIX + key);
        return null;
      }
      const draft = normalise(parsed.draft);
      return hasDraftText(draft) ? draft : null;
    } catch {
      store.removeItem(STORAGE_PREFIX + key);
      return null;
    }
  }) ?? null;

  const entry = (key: string): Entry => {
    let found = memory.get(key);
    if (!found) {
      found = { draft: loadStored(key), files: [], touched: false };
      memory.set(key, found);
    }
    return found;
  };

  const persist = (key: string) => guarded(store => {
    prepare();
    const draft = memory.get(key)?.draft;
    const storageKey = STORAGE_PREFIX + key;
    if (!draft || !hasDraftText(draft)) { store.removeItem(storageKey); return; }
    const raw = JSON.stringify({ savedAt: now(), draft });
    // Too large for a fair share of the origin's storage: keep it for this session only, and make
    // sure an older, shorter copy cannot come back after a reload.
    if (raw.length > MAX_DRAFT_CHARS) { store.removeItem(storageKey); return; }
    try {
      store.setItem(storageKey, raw);
    } catch {
      prune(Math.floor(MAX_STORED_DRAFTS / 2));
      store.setItem(storageKey, raw);
    }
  });

  const flush = () => {
    if (timer) { clearTimeout(timer); timer = null; }
    const keys = [...dirty];
    dirty.clear();
    keys.forEach(persist);
  };
  const schedule = (key: string) => {
    dirty.add(key);
    if (debounceMs <= 0) { flush(); return; }
    if (timer) clearTimeout(timer);
    timer = setTimeout(flush, debounceMs);
  };

  return {
    /** The draft key of a conversation within an inbox workspace (none for the plain renderer). */
    key: (workspace: string | undefined, conversationId: string) => `${encodeURIComponent(workspace ?? "")}|${conversationId}`,
    read: (key: string): ComposerDraft | null => entry(key).draft,
    /** Replaces the draft; an emptied draft leaves storage at once, a blank one leaves memory too. */
    write: (key: string, draft: ComposerDraft | null) => {
      const target = entry(key);
      target.draft = draft && !isBlank(draft) ? { ...draft } : null;
      target.touched = true;
      if (target.draft && hasDraftText(target.draft)) schedule(key);
      else { dirty.delete(key); persist(key); }
      emit();
    },
    files: (key: string): DraftFile[] => memory.get(key)?.files ?? [],
    setFiles: (key: string, update: DraftFile[] | ((files: DraftFile[]) => DraftFile[])) => {
      const target = entry(key);
      target.files = typeof update === "function" ? update(target.files) : update;
      target.touched = true;
      emit();
    },
    /** After a successful send: the part that went out leaves; the other mode's text stays. */
    clearSent: (key: string, mode: ComposerMode) => {
      const target = entry(key);
      const draft = target.draft ?? { ...EMPTY_DRAFT, mode };
      if (mode === "note") target.draft = { ...draft, note: "", noteBody: "" };
      else { target.draft = { ...draft, body: "", html: null, subject: null, recipient: null, from: null }; target.files = []; }
      if (isBlank(target.draft)) target.draft = null;
      dirty.delete(key);
      persist(key);
      emit();
    },
    /** Whether anything written or attached during this page session has not been sent. */
    hasUnsentWork: () => [...memory.values()].some(item => item.touched && (hasDraftText(item.draft) || item.files.length > 0)),
    flush,
    prune,
    subscribe: (listener: () => void) => { listeners.add(listener); return () => { listeners.delete(listener); }; },
    version: () => version,
    /** Forgets the session's drafts (storage is left alone); used by tests. */
    reset: () => { if (timer) clearTimeout(timer); timer = null; dirty.clear(); memory.clear(); pruned = false; emit(); },
  };
}

function defaultStorage(): Storage | null {
  return typeof window === "undefined" ? null : window.localStorage;
}

/** The inbox's one store: shared by every inbox mount on the page, so a remount keeps the drafts. */
export const composerDrafts = createDraftStore();

let guardInstalled = false;
/**
 * Writes pending drafts when the page goes away, and asks before unloading while text or files from
 * this session are still unsent. Installed once per page, however many inboxes mount.
 */
export function installDraftUnloadGuard(store: DraftStore = composerDrafts) {
  if (guardInstalled || typeof window === "undefined") return;
  guardInstalled = true;
  window.addEventListener("pagehide", () => store.flush());
  document.addEventListener("visibilitychange", () => { if (document.visibilityState === "hidden") store.flush(); });
  window.addEventListener("beforeunload", event => {
    store.flush();
    if (!store.hasUnsentWork()) return;
    event.preventDefault();
    // Older browsers only prompt when returnValue is set.
    event.returnValue = "";
  });
}
