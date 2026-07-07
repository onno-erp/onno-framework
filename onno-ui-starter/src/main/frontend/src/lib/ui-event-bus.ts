import { streamUiEvents } from "@/lib/api";
import type { UiEvent } from "@/lib/types";

/**
 * One shared SSE connection per origin, across every browser tab.
 *
 * Each tab used to open its own long-lived `/api/events` stream. Browsers cap concurrent
 * connections per origin (~6 over HTTP/1.1, and that pool is shared across every tab of the
 * origin) while the server holds each stream open forever — so a handful of open tabs exhausted
 * the pool: extra tabs never received live updates and even ordinary API calls were starved
 * behind the parked streams (issue #186).
 *
 * Instead, a single "leader" tab — elected with the Web Locks API — holds the one stream and
 * rebroadcasts every event to the other tabs over a {@link BroadcastChannel}. When the leader tab
 * closes, the browser releases its lock and another tab transparently takes over. Browsers without
 * Web Locks or BroadcastChannel fall back to the original per-tab stream.
 */

const CHANNEL_NAME = "onno-ui-events";
const LEADER_LOCK = "onno-ui-events-leader";
const RECONNECT_MS = 3000;

type Listener = (event: UiEvent) => void;

const listeners = new Set<Listener>();
let channel: BroadcastChannel | null = null;
let releaseLock: (() => void) | null = null;
let stopStream: (() => void) | null = null;
let running = false;

function deliver(event: UiEvent) {
  for (const listener of listeners) listener(event);
}

/**
 * Hold the single SSE stream and reconnect on drop. The leader also fans every event out over the
 * BroadcastChannel; the fallback (no cross-tab primitives) keeps the event purely local.
 */
function startStream(broadcast: boolean) {
  let stopped = false;
  let retry: number | undefined;

  const loop = () => {
    const controller = new AbortController();
    stopStream = () => {
      stopped = true;
      if (retry) window.clearTimeout(retry);
      controller.abort();
    };
    streamUiEvents((event) => {
      deliver(event);
      if (broadcast) channel?.postMessage(event);
    }, controller.signal)
      .catch((err: unknown) => {
        // A 401 means the session lapsed — streamUiEvents already handed off to auth recovery
        // (redirect/re-auth). Stop reconnecting so we don't re-open /api/events every few seconds
        // and flood the console with 401s until then; a fresh mount after re-auth restarts the loop.
        // Any other error (a dropped stream, a server restart) falls through to reconnect.
        if ((err as { status?: number })?.status === 401) stopped = true;
      })
      .finally(() => {
        if (!stopped) retry = window.setTimeout(loop, RECONNECT_MS);
      });
  };

  loop();
}

function start() {
  if (running) return;
  running = true;

  const canShare =
    typeof BroadcastChannel !== "undefined" &&
    typeof navigator !== "undefined" &&
    "locks" in navigator;

  if (!canShare) {
    // No cross-tab primitives — degrade to the original per-tab stream.
    startStream(false);
    return;
  }

  channel = new BroadcastChannel(CHANNEL_NAME);
  channel.onmessage = (event: MessageEvent<UiEvent>) => deliver(event.data);

  // Become leader as soon as the lock is free. request() queues behind the current holder; its
  // callback holds the lock (and thus leadership) until we resolve `released`. A leader tab that
  // closes releases the lock automatically, so a queued follower takes over with no gap to manage.
  navigator.locks
    .request(LEADER_LOCK, () =>
      new Promise<void>((resolve) => {
        if (!running) {
          resolve(); // torn down before we won the election — don't open a stream
          return;
        }
        releaseLock = resolve;
        startStream(true);
      })
    )
    .catch(() => {
      // Lock request rejected (unexpected) — leave the tab as a passive channel follower.
    });
}

function stop() {
  running = false;
  stopStream?.();
  stopStream = null;
  channel?.close();
  channel = null;
  releaseLock?.(); // frees the lock so another tab can lead
  releaseLock = null;
}

/**
 * Subscribe to live UI events. The first subscriber opens (or joins) the shared stream; the last
 * one to unsubscribe tears it down. Returns an unsubscribe function.
 */
export function subscribeUiEvents(listener: Listener): () => void {
  listeners.add(listener);
  start();
  return () => {
    listeners.delete(listener);
    if (listeners.size === 0) stop();
  };
}
