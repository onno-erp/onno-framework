/**
 * Hand-off channel between a RefSelect's "+ New" and the entity form it opens.
 *
 * When the user quick-creates a record from a ref picker, the picker registers a pending
 * request here; when the create form saves, it consumes the request and publishes the new
 * record's id — so the field ends up set to what the user just created, instead of making
 * them find it in the picker again.
 *
 * The result is delivered as state (a short-lived "delivered" slot) plus a window event,
 * NOT a captured callback: DivKit islands remount on pane churn, and in single-pane layouts
 * the originating form unmounts entirely while the create form is open — a callback would
 * resolve into a dead component. The picker adopts the delivered id either live (event) or
 * on remount (slot check), whichever happens.
 *
 * Single-slot on purpose: only the most recent "+ New" can be outstanding, and a manual pick
 * or a cancelled form clears it so a later unrelated create isn't hijacked into the field.
 */

/** Fired on window after a quick-created record is saved; detail is {@link Delivered}. */
export const QUICK_CREATED_EVENT = "onno:quickcreated";

type Pending = { key: string; token: number };
export type Delivered = { key: string; id: string; token: number; at: number };

// How long a result stays adoptable. Claims are idempotent within this window on purpose:
// closing the create pane can remount the originating form (DivKit islands remount on pane
// churn), wiping a value that was adopted moments earlier — the remounted picker must be able
// to re-claim. Kept short so a genuinely unrelated later form can't pick up a stale id.
const DELIVERED_TTL_MS = 10_000;

let pending: Pending | null = null;
let delivered: Delivered | null = null;
let tokenSeq = 0;

// One key space for catalogs and documents alike ("catalogs/suppliers").
function keyOf(kind: string, name: string): string {
  return `${kind}/${name}`;
}

/**
 * Register a picker's "+ New" for the given target. Returns a token identifying this request;
 * the (still-mounted) requester uses it to claim the result over other pickers of the same target.
 */
export function requestQuickCreate(kind: string, name: string): number {
  tokenSeq += 1;
  pending = { key: keyOf(kind, name), token: tokenSeq };
  delivered = null;
  return tokenSeq;
}

/** Drop the pending request and any delivered result for this target (manual pick, cancelled create form). */
export function cancelQuickCreate(kind: string, name: string): void {
  const key = keyOf(kind, name);
  if (pending?.key === key) pending = null;
  if (delivered?.key === key) delivered = null;
}

/**
 * Called by the entity form after a successful create. When a picker was waiting for this
 * target, publishes the result (slot + {@link QUICK_CREATED_EVENT}) and returns true — the
 * caller should then skip navigating to the new record (the user stays on the form they were
 * filling in). Returns false for an ordinary create.
 */
export function consumeQuickCreate(kind: string, name: string, id: string): boolean {
  if (pending?.key !== keyOf(kind, name)) return false;
  delivered = { key: pending.key, id, token: pending.token, at: Date.now() };
  pending = null;
  window.dispatchEvent(new CustomEvent<Delivered>(QUICK_CREATED_EVENT, { detail: delivered }));
  return true;
}

/**
 * Claim a delivered quick-create result for this target. A picker may claim it when it made
 * the request (token match) or when it's an empty same-target picker after a remount (the
 * requester instance is gone, so the token is unknowable). Deliberately does NOT clear the
 * slot: the slot expires by TTL instead, so a picker that adopted the id and was then
 * remounted (losing its state) can claim the same result again. Callers must treat repeated
 * claims of the same id as a no-op.
 */
export function claimQuickCreated(
  kind: string,
  name: string,
  requestToken: number | null,
  isEmpty: boolean
): string | null {
  if (!delivered || delivered.key !== keyOf(kind, name)) return null;
  if (Date.now() - delivered.at > DELIVERED_TTL_MS) {
    delivered = null;
    return null;
  }
  if (delivered.token !== requestToken && !isEmpty) return null;
  return delivered.id;
}
