import { createContext, useContext, useSyncExternalStore } from "react";
import type { EntityRecord } from "@onno/ui";

/** An open composer window: the conversation it writes to, kept while the inbox shows other chats. */
export type ComposerWindow = { key: string; workspace: string; conversation: EntityRecord; contactName: string };

/**
 * The inbox's open composer windows, one per conversation (by its draft key, `composerDrafts.key`). A window
 * belongs to its conversation, like a draft: choosing another chat leaves it open, docked or minimised where it
 * was, still writing to the chat it was opened for. The draft itself lives in `composerDrafts`.
 */
export function createComposerWindows() {
  let open = new Map<string, ComposerWindow>();
  let version = 0;
  const listeners = new Set<() => void>();
  const emit = () => { version++; listeners.forEach(listener => listener()); };
  return {
    isOpen: (key: string) => open.has(key),
    open: (window: ComposerWindow) => { open = new Map(open).set(window.key, window); emit(); },
    close: (key: string) => {
      if (!open.has(key)) return;
      open = new Map(open); open.delete(key); emit();
    },
    /** The workspace's windows, oldest first. */
    list: (workspace: string): ComposerWindow[] => [...open.values()].filter(window => window.workspace === workspace),
    subscribe: (listener: () => void) => { listeners.add(listener); return () => { listeners.delete(listener); }; },
    version: () => version,
    /** Forgets every window; used by tests. */
    reset: () => { open = new Map(); emit(); },
  };
}

/** The page's one store, shared by every inbox mount, so a remount finds its windows. */
export const composerWindows = createComposerWindows();

export function useComposerWindows() {
  useSyncExternalStore(composerWindows.subscribe, composerWindows.version, composerWindows.version);
  return composerWindows;
}

/**
 * Set by an inbox that hosts composer windows (`CrmInbox`): its composers keep their window per conversation. A
 * composer outside one (a conversation page, an app's own screen) keeps a window of its own while it is mounted.
 */
export const ComposerWindowHost = createContext<{ workspace: string } | null>(null);
export const useComposerWindowHost = () => useContext(ComposerWindowHost);
