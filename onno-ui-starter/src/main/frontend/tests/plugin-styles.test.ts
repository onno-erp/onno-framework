import { beforeEach, describe, expect, it, vi } from "vitest";

// plugin-loader pulls in plugin-host (host UI primitives, transitively maplibre) for loadPlugins;
// injectPluginStyles doesn't need any of it.
vi.mock("@/lib/plugin-host", () => ({ installPluginHost: () => {} }));

const { injectPluginStyles } = await import("@/lib/plugin-loader");

/**
 * Plugin stylesheets are a second, unscoped Tailwind utilities pass. Their selectors tie with the
 * host's on specificity, so document order decides conflicts: appended after the host sheet, a
 * plugin's bare `.flex-col` would beat the host's `sm:flex-row` media rule on host markup (this
 * broke the desktop date-range popover into a stacked column). The loader must therefore insert
 * plugin links BEFORE the first host style.
 */
describe("injectPluginStyles", () => {
  beforeEach(() => {
    document.head.innerHTML = "";
  });

  const links = () =>
    [...document.head.querySelectorAll("link[rel=stylesheet], style")].map(
      (el) => (el as HTMLLinkElement).href?.split("/").pop() || el.tagName.toLowerCase()
    );

  it("inserts plugin links before the host stylesheet link, preserving given order", () => {
    const host = document.createElement("link");
    host.rel = "stylesheet";
    host.href = "/assets/index-abc.css";
    document.head.appendChild(host);

    injectPluginStyles(["/onno-plugins/onno-widgets.css", "/onno-plugins/extra.css"]);

    expect(links()).toEqual(["onno-widgets.css", "extra.css", "index-abc.css"]);
  });

  it("inserts before injected <style> tags (Vite dev)", () => {
    const style = document.createElement("style");
    style.textContent = ".flex-col{flex-direction:column}";
    document.head.appendChild(style);

    injectPluginStyles(["/onno-plugins/onno-widgets.css"]);

    expect(links()).toEqual(["onno-widgets.css", "style"]);
  });

  it("is idempotent per URL and appends when no host style exists yet", () => {
    injectPluginStyles(["/onno-plugins/onno-widgets.css"]);
    injectPluginStyles(["/onno-plugins/onno-widgets.css"]);

    expect(links()).toEqual(["onno-widgets.css"]);
  });
});
