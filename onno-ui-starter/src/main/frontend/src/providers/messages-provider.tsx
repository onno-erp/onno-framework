import { createContext, useContext, useEffect, useMemo, useState } from "react";
import { api } from "@/lib/api";
import { DEFAULT_MESSAGES, makeTranslate, type Translate } from "@/lib/messages";

/**
 * Supplies the framework's chrome strings (action buttons, dialogs, login form, empty/loading
 * states, validation) to the whole app. The server is the label source: it fetches {@code
 * GET /api/config} once and overlays the returned {@code messages} map on the bundled English
 * {@link DEFAULT_MESSAGES} — so a deployment that set {@code onno.ui.messages} gets its language
 * everywhere, while the bundle keeps the shell readable before config loads or if the fetch fails.
 *
 * Mirrors {@link BrandingProvider}: fetch-and-apply, fall back to defaults, never block render.
 */
const MessagesContext = createContext<Translate>(makeTranslate(DEFAULT_MESSAGES));

export function MessagesProvider({ children }: { children: React.ReactNode }) {
  const [messages, setMessages] = useState<Record<string, string>>(DEFAULT_MESSAGES);

  useEffect(() => {
    let cancelled = false;
    api
      .getConfig()
      .then((cfg) => {
        if (cancelled || !cfg?.messages) return;
        // Server map wins per key; any key the server omits keeps its bundled English default.
        setMessages({ ...DEFAULT_MESSAGES, ...cfg.messages });
      })
      .catch(() => {
        // No config / offline — keep the bundled English defaults.
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const t = useMemo(() => makeTranslate(messages), [messages]);
  return <MessagesContext.Provider value={t}>{children}</MessagesContext.Provider>;
}

/** The chrome translator: {@code t("action.save")}, {@code t("ref.new", { name })}. */
export const useMessages = (): Translate => useContext(MessagesContext);
