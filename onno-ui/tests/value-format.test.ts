import { afterEach, describe, expect, it } from "vitest";
import { formatValue } from "@/lib/cell-format";
import { DEFAULT_MESSAGES, makeTranslate } from "@/lib/messages";
import { setMessagesOutsideReact } from "@/lib/messages-outside-react";

const publishWidgetLocale = (locale: string | undefined) => setMessagesOutsideReact(makeTranslate(DEFAULT_MESSAGES), locale);

afterEach(() => publishWidgetLocale(undefined));

describe("typed value formats", () => {
  it("formats money, numbers and percentages in the app's locale", () => {
    publishWidgetLocale("en-US");
    expect(formatValue("1250", { money: "USD" })).toBe("$1,250.00");
    expect(formatValue("1250.456", { number: { decimals: 0 } })).toBe("1,250");
    expect(formatValue("1250.4", { number: { decimals: 2, grouping: false } })).toBe("1250.40");
    expect(formatValue("0.125", { percent: { decimals: 1 } })).toBe("12.5%");
  });

  it("formats dates by style or by Intl options", () => {
    publishWidgetLocale("en-US");
    expect(formatValue("2026-07-04", { date: "medium" })).toBe("Jul 4, 2026");
    expect(formatValue("2026-07-04T10:30:00", { date: { month: "short", day: "numeric" } })).toBe("Jul 4");
  });

  it("leaves values that do not fit the format to the caller", () => {
    expect(formatValue("n/a", { money: "USD" })).toBeNull();
    expect(formatValue("not a date", { date: "short" })).toBeNull();
    expect(formatValue("12", undefined)).toBeNull();
  });
});
