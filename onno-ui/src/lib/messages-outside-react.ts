import { DEFAULT_MESSAGES, makeTranslate, type Translate } from "./messages";

/**
 * The chrome translator and locale for code outside React — module helpers such as the value
 * formatters and the exported `text`/`locale`, which have no access to context. {@link MessagesProvider}
 * is the one source: it sets these whenever its messages or locale change. Components read the
 * provider itself (`useMessages`, `useAppLocale`).
 */
let current: { translate: Translate; locale: string | undefined } = {
  translate: makeTranslate(DEFAULT_MESSAGES),
  locale: undefined,
};

/** Called by {@link MessagesProvider} with what it provides. */
export function setMessagesOutsideReact(translate: Translate, locale: string | undefined): void {
  current = { translate, locale };
}

/** The configured locale (`onno.ui.locale`), or undefined (the browser's) before config arrives or when none is set. */
export function localeOutsideReact(): string | undefined {
  return current.locale;
}

/** The chrome translator, as {@link MessagesProvider} last provided it. */
export function translateOutsideReact(): Translate {
  return current.translate;
}
