import { afterEach, beforeEach, expect, it } from "vitest";
import { createDraftStore, DRAFT_TTL_MS, EMPTY_DRAFT, MAX_DRAFT_CHARS, MAX_STORED_DRAFTS, type ComposerDraft } from "../../onno-crm-starter/src/main/ui/composerDrafts";

const PREFIX = "onno.crm.draft.v1|";
let clock = Date.parse("2026-11-10T10:00:00Z");
const store = (storage: () => Storage | null = () => window.localStorage) => createDraftStore({ storage, now: () => clock, debounceMs: 0 });
const draft = (change: Partial<ComposerDraft>): ComposerDraft => ({ ...EMPTY_DRAFT, ...change });
const stored = () => Object.keys(window.localStorage).filter(key => key.startsWith(PREFIX));

beforeEach(() => { window.localStorage.clear(); clock = Date.parse("2026-11-10T10:00:00Z"); });
afterEach(() => window.localStorage.clear());

it("keeps each conversation's text, subject, recipient, sender and mode across a reload", () => {
  const first = store();
  const a = first.key("agency", "chat-a"), b = first.key("agency", "chat-b");
  first.write(a, draft({ body: "Dear Anna", subject: "Villa dates", recipient: "anna@example.com", from: "wedding" }));
  first.write(b, draft({ mode: "note", note: "Call back @Lena", noteBody: "Call back @[Lena](catalogs/people/1)" }));
  expect(first.key("agency", "chat-a")).not.toBe(first.key("other", "chat-a"));

  const reloaded = store();
  expect(reloaded.read(a)).toEqual(draft({ body: "Dear Anna", subject: "Villa dates", recipient: "anna@example.com", from: "wedding" }));
  expect(reloaded.read(b)).toMatchObject({ mode: "note", note: "Call back @Lena", noteBody: "Call back @[Lena](catalogs/people/1)" });
  expect(reloaded.read(reloaded.key("other", "chat-a"))).toBeNull();
});

it("forgets a draft the writer empties, and only the sent part after a send", () => {
  const drafts = store();
  const key = drafts.key("", "chat");
  drafts.write(key, draft({ body: "Hello" }));
  expect(stored()).toHaveLength(1);
  drafts.write(key, draft({ body: "" }));
  expect(stored()).toHaveLength(0);
  expect(drafts.read(key)).toBeNull();

  drafts.write(key, draft({ body: "Reply text", subject: "Re: plan", from: "office", note: "Internal remark", noteBody: "Internal remark" }));
  drafts.setFiles(key, [{ url: "/api/media/a.pdf", filename: "a.pdf", contentType: "application/pdf", size: 4, image: false }]);
  drafts.clearSent(key, "reply");
  expect(drafts.read(key)).toMatchObject({ body: "", subject: null, from: null, note: "Internal remark" });
  expect(drafts.files(key)).toEqual([]);
  drafts.clearSent(key, "note");
  expect(drafts.read(key)).toBeNull();
  expect(stored()).toHaveLength(0);
});

it("keeps attached files in memory only and reports unsent work from this session", () => {
  const drafts = store();
  const key = drafts.key("", "chat");
  expect(drafts.hasUnsentWork()).toBe(false);
  drafts.setFiles(key, [{ url: "/api/media/a.pdf", filename: "a.pdf", contentType: "application/pdf", size: 4, image: false }]);
  expect(drafts.hasUnsentWork()).toBe(true);
  expect(stored()).toHaveLength(0);
  expect(store().files(key)).toEqual([]);

  // A draft restored from an earlier visit is already safe in storage: it does not hold the page.
  const earlier = store();
  earlier.write(key, draft({ body: "Saved earlier" }));
  const later = store();
  expect(later.read(key)?.body).toBe("Saved earlier");
  expect(later.hasUnsentWork()).toBe(false);
});

it("drops expired and unreadable entries and keeps only the newest drafts", () => {
  const drafts = store();
  drafts.write(drafts.key("", "old"), draft({ body: "Old" }));
  window.localStorage.setItem(`${PREFIX}|broken`, "{not json");
  clock += DRAFT_TTL_MS + 1;
  const later = store();
  expect(later.read(later.key("", "old"))).toBeNull();
  expect(stored()).toEqual([]);

  for (let index = 0; index < MAX_STORED_DRAFTS + 5; index++) {
    clock += 1000;
    later.write(later.key("", `chat-${index}`), draft({ body: `Draft ${index}` }));
  }
  later.prune();
  expect(stored()).toHaveLength(MAX_STORED_DRAFTS);
  expect(window.localStorage.getItem(`${PREFIX}|chat-0`)).toBeNull();
  expect(window.localStorage.getItem(`${PREFIX}|chat-${MAX_STORED_DRAFTS + 4}`)).not.toBeNull();
});

it("keeps an oversized draft for the session without leaving an older copy in storage", () => {
  const drafts = store();
  const key = drafts.key("", "long");
  drafts.write(key, draft({ body: "Short version" }));
  expect(stored()).toHaveLength(1);
  const long = "x".repeat(MAX_DRAFT_CHARS);
  drafts.write(key, draft({ body: long }));
  expect(drafts.read(key)?.body).toBe(long);
  expect(stored()).toHaveLength(0);
});

it("works in memory when storage is unavailable or full", () => {
  const blocked = store(() => { throw new Error("SecurityError"); });
  const key = blocked.key("", "chat");
  blocked.write(key, draft({ body: "Still here" }));
  expect(blocked.read(key)?.body).toBe("Still here");

  const full = { length: 0, key: () => null, getItem: () => null, removeItem: () => {}, clear: () => {},
    setItem: () => { throw new DOMException("quota", "QuotaExceededError"); } } as Storage;
  const quota = store(() => full);
  quota.write(key, draft({ body: "Not persisted" }));
  expect(quota.read(key)?.body).toBe("Not persisted");
});
