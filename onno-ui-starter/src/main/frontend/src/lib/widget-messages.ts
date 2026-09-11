import { DEFAULT_MESSAGES, makeTranslate, type Translate } from "@/lib/messages";

/**
 * The chrome strings a widget plugin translates against, as a tiny subscribable store.
 *
 * Widgets are separate ESM modules with no access to the host's React context, so
 * {@link MessagesProvider}'s `Translate` cannot reach them the way it reaches the SPA's own
 * components. This is the bridge: the provider publishes the resolved map here whenever it changes
 * (bundled English first, then the server's `onno.ui.messages` overlay once `/api/config` lands),
 * and the SDK's `useTranslate` subscribes through `useSyncExternalStore`. A widget that renders
 * before config arrives therefore shows English and re-renders into the deployment's language by
 * itself — the same behaviour the rest of the shell has.
 */
let translate: Translate = makeTranslate(DEFAULT_MESSAGES);
const listeners = new Set<() => void>();

/** Called by {@link MessagesProvider} when the resolved message map changes. */
export function publishWidgetMessages(messages: Record<string, string>): void {
  translate = makeTranslate(messages);
  for (const listener of listeners) listener();
}

/** The current translate function. Stable identity between publishes, so it is a valid store snapshot. */
export function widgetTranslate(): Translate {
  return translate;
}

export function subscribeWidgetMessages(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}
