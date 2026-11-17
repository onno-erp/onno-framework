import { DEFAULT_MESSAGES } from "@/lib/messages";

/**
 * The text exports a CRM screen imports from `@onno/ui`, for tests that mock `@onno/ui`.
 *
 * Spread into the `vi.mock` factory rather than declared beside it: factories are hoisted above the
 * module's own consts, so a top-level helper is not yet initialized when one runs. Resolving through
 * the real {@link DEFAULT_MESSAGES} keeps assertions written against the English copy meaningful —
 * and makes a key that was never given a default fail loudly, as the key string.
 */
const uiText = (key: string) => DEFAULT_MESSAGES[key] ?? key;

export const text = uiText;
export const useTranslate = () => uiText;
/** The configured locale: none in tests, so `Intl` formats as it would without `onno.ui.locale`. */
export const locale = (): string | undefined => undefined;
export const useLocale = (): string | undefined => undefined;
