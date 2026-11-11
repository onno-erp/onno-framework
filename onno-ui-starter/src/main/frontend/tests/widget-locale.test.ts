import { afterEach, expect, it } from "vitest";
import { installPluginHost } from "@/lib/plugin-host";
import { formatTimestampDefault } from "@/lib/cell-format";
import { publishWidgetLocale, widgetLocale } from "@/lib/widget-messages";

afterEach(() => publishWidgetLocale(undefined));

it("formats list timestamps in onno.ui.locale once config has published it", () => {
  publishWidgetLocale("en-GB");
  expect(formatTimestampDefault("2026-11-06T11:02:00")).toMatch(/^6 Nov 2026/);
  publishWidgetLocale("en-US");
  expect(formatTimestampDefault("2026-11-06T11:02:00")).toMatch(/^Nov 6, 2026/);
});

it("hands widgets the configured locale and tells them when it changes", () => {
  const host = installPluginHost();
  let changes = 0;
  const stop = host.messages.subscribe(() => changes++);
  expect(host.messages.locale()).toBeUndefined();
  publishWidgetLocale("en-GB");
  publishWidgetLocale("en-GB");
  expect(host.messages.locale()).toBe("en-GB");
  expect(widgetLocale()).toBe("en-GB");
  expect(changes).toBe(1);
  stop();
});
