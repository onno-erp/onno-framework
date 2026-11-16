import { createContext, createElement, useContext, useMemo, useRef, useState, type ReactNode } from "react";

/**
 * Hand-off between a RefSelect's "+ New" and the entity form it opens.
 *
 * When the user quick-creates a record from a ref picker, the picker registers a pending request;
 * when the create form saves, it consumes the request and delivers the new record's id — so the
 * field ends up set to what the user just created, instead of making them find it in the picker
 * again.
 *
 * The result is delivered as state held by {@link QuickCreateProvider} (a short-lived "delivered"
 * slot), NOT a captured callback: forms remount on pane churn, and in single-pane layouts the
 * originating form unmounts entirely while the create form is open — a callback would resolve into
 * a dead component. The picker adopts the delivered id either live (the slot changes) or on remount
 * (it finds the slot filled), whichever happens.
 *
 * Single-slot on purpose: only the most recent "+ New" can be outstanding, and a manual pick or a
 * cancelled form clears it so a later unrelated create isn't hijacked into the field.
 */

export type Delivered = { key: string; id: string; token: number; at: number };

export type QuickCreate = {
  /**
   * Register a picker's "+ New" for the given target. Returns a token identifying this request;
   * the (still-mounted) requester uses it to claim the result over other pickers of the same target.
   */
  request: (kind: string, name: string) => number;
  /** Drop the pending request and any delivered result for this target (manual pick, cancelled create form). */
  cancel: (kind: string, name: string) => void;
  /**
   * Called by the entity form after a successful create. When a picker was waiting for this target,
   * delivers the result and returns true — the caller should then skip navigating to the new record
   * (the user stays on the form they were filling in). Returns false for an ordinary create.
   */
  consume: (kind: string, name: string, id: string) => boolean;
  /** The latest delivered result (a picker re-checks its claim whenever this changes). */
  delivered: Delivered | null;
  /**
   * Claim the delivered result for this target. A picker may claim it when it made the request
   * (token match) or when it's an empty same-target picker after a remount (the requester instance
   * is gone, so the token is unknowable). Deliberately does NOT clear the slot: the slot expires by
   * TTL instead, so a picker that adopted the id and was then remounted (losing its state) can claim
   * the same result again. Callers must treat repeated claims of the same id as a no-op.
   */
  claim: (kind: string, name: string, requestToken: number | null, isEmpty: boolean) => string | null;
};

// How long a result stays adoptable. Claims are idempotent within this window on purpose:
// closing the create pane can remount the originating form (forms remount on pane churn),
// wiping a value that was adopted moments earlier — the remounted picker must be able
// to re-claim. Kept short so a genuinely unrelated later form can't pick up a stale id.
const DELIVERED_TTL_MS = 10_000;

// One key space for catalogs and documents alike ("catalogs/suppliers").
function keyOf(kind: string, name: string): string {
  return `${kind}/${name}`;
}

/** Outside a provider (a picker rendered on its own): "+ New" just opens the form. */
const QuickCreateContext = createContext<QuickCreate>({
  request: () => 0,
  cancel: () => {},
  consume: () => false,
  delivered: null,
  claim: () => null,
});

/** Hosts the quick-create hand-off for everything below it (the shell). */
export function QuickCreateProvider({ children }: { children: ReactNode }) {
  const pending = useRef<{ key: string; token: number } | null>(null);
  const tokenSeq = useRef(0);
  const [delivered, setDelivered] = useState<Delivered | null>(null);
  const deliveredRef = useRef(delivered);
  deliveredRef.current = delivered;

  const value = useMemo<QuickCreate>(() => ({
    request: (kind, name) => {
      tokenSeq.current += 1;
      pending.current = { key: keyOf(kind, name), token: tokenSeq.current };
      deliveredRef.current = null;
      setDelivered(null);
      return tokenSeq.current;
    },
    cancel: (kind, name) => {
      const key = keyOf(kind, name);
      if (pending.current?.key === key) pending.current = null;
      if (deliveredRef.current?.key === key) {
        deliveredRef.current = null;
        setDelivered(null);
      }
    },
    consume: (kind, name, id) => {
      const request = pending.current;
      if (request?.key !== keyOf(kind, name)) return false;
      const result = { key: request.key, id, token: request.token, at: Date.now() };
      pending.current = null;
      deliveredRef.current = result;
      setDelivered(result);
      return true;
    },
    delivered,
    claim: (kind, name, requestToken, isEmpty) => {
      const result = deliveredRef.current;
      if (!result || result.key !== keyOf(kind, name)) return null;
      if (Date.now() - result.at > DELIVERED_TTL_MS) return null;
      if (result.token !== requestToken && !isEmpty) return null;
      return result.id;
    },
  }), [delivered]);

  return createElement(QuickCreateContext.Provider, { value }, children);
}

/** The quick-create hand-off of the shell this component is in. */
export function useQuickCreate(): QuickCreate {
  return useContext(QuickCreateContext);
}
