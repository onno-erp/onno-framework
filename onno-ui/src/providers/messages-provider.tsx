import { createContext, useContext, useEffect, useLayoutEffect, useMemo, useState } from "react";
import { I18nProvider } from "react-aria-components";
import { api } from "../lib/api";
import { DEFAULT_MESSAGES, makeTranslate, type Translate } from "../lib/messages";
import { setMessagesOutsideReact } from "../lib/messages-outside-react";
import { publishDisplayTimeZone } from "../lib/time-zone";

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
const LocaleContext = createContext<string | undefined>(undefined);
const ListExportRolesContext = createContext<readonly string[]>([]);

export function MessagesProvider({ children }: { children: React.ReactNode }) {
  const [messages, setMessages] = useState<Record<string, string>>(DEFAULT_MESSAGES);
  const [locale, setLocale] = useState<string | undefined>(undefined);
  const [listExportRoles, setListExportRoles] = useState<readonly string[]>([]);

  useEffect(() => {
    let cancelled = false;
    api
      .getConfig()
      .then((cfg) => {
        if (cancelled || !cfg) return;
        setLocale(cfg.locale || undefined);
        setListExportRoles(cfg.listExportRoles ?? []);
        publishDisplayTimeZone(cfg.timeZone || undefined);
        if (!cfg.messages) return;
        // Server map wins per key; any key the server omits keeps its bundled English default.
        const resolved = { ...DEFAULT_MESSAGES, ...cfg.messages };
        setMessages(resolved);
      })
      .catch(() => {
        // No config / offline — keep the bundled English defaults.
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const t = useMemo(() => makeTranslate(messages), [messages]);
  // Code outside React (formatters, the exported `text`) reads what this provides.
  useLayoutEffect(() => setMessagesOutsideReact(t, locale), [t, locale]);
  return (
    <LocaleContext.Provider value={locale}>
      <I18nProvider locale={locale}>
        <ListExportRolesContext.Provider value={listExportRoles}>
          <MessagesContext.Provider value={t}>{children}</MessagesContext.Provider>
        </ListExportRolesContext.Provider>
      </I18nProvider>
    </LocaleContext.Provider>
  );
}

/** The chrome translator: {@code t("action.save")}, {@code t("ref.new", { name })}. */
export const useMessages = (): Translate => useContext(MessagesContext);
/** The configured chrome locale from {@code onno.ui.locale}, if any. */
export const useAppLocale = (): string | undefined => useContext(LocaleContext);
export const useListExportRoles = (): readonly string[] => useContext(ListExportRolesContext);
