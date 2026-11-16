import { afterEach, expect, it } from "vitest";
import { formatTimestampDefault } from "@/lib/cell-format";
import { DEFAULT_MESSAGES, makeTranslate } from "@/lib/messages";
import { localeOutsideReact, setMessagesOutsideReact } from "@/lib/messages-outside-react";

afterEach(() => setMessagesOutsideReact(makeTranslate(DEFAULT_MESSAGES), undefined));

it("formats list timestamps in onno.ui.locale once the provider has set it", () => {
  setMessagesOutsideReact(makeTranslate(DEFAULT_MESSAGES), "en-GB");
  expect(formatTimestampDefault("2026-11-06T11:02:00")).toMatch(/^6 Nov 2026/);
  setMessagesOutsideReact(makeTranslate(DEFAULT_MESSAGES), "en-US");
  expect(formatTimestampDefault("2026-11-06T11:02:00")).toMatch(/^Nov 6, 2026/);
});

it("leaves the locale to the browser until a provider sets one", () => {
  expect(localeOutsideReact()).toBeUndefined();
});
