import { DEFAULT_MESSAGES } from "@/lib/messages";

/**
 * The translate exports a CRM widget imports from `@onno/widget-sdk`, for tests that mock the SDK.
 *
 * Spread into the `vi.mock` factory rather than declared beside it: factories are hoisted above the
 * module's own consts, so a top-level helper is not yet initialized when one runs. Resolving through
 * the real {@link DEFAULT_MESSAGES} keeps assertions written against the English copy meaningful —
 * and makes a key that was never given a default fail loudly, as the key string.
 */
const widgetText = (key: string) => DEFAULT_MESSAGES[key] ?? key;

export const text = widgetText;
export const useTranslate = () => widgetText;
