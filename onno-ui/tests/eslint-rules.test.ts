// The onno ESLint rules (@onno/ui/eslint), run through ESLint's RuleTester under Vitest.
import { RuleTester } from "eslint";
import tseslint from "typescript-eslint";
import onno, { configs, rules } from "../eslint-plugin/index.js";

RuleTester.describe = describe;
RuleTester.it = it;
RuleTester.itOnly = it.only;

const tester = new RuleTester({
  languageOptions: {
    parser: tseslint.parser,
    ecmaVersion: "latest",
    sourceType: "module",
    parserOptions: { ecmaFeatures: { jsx: true } },
  },
});

tester.run("no-private-imports", rules["no-private-imports"], {
  valid: [
    `import { EntityList, useRecords } from "@onno/ui";`,
    `import { Truck } from "@onno/ui/icons";`,
    `import { onno } from "@onno/ui/vite";`,
    `import onno from "@onno/ui/eslint";`,
    `import { CrmInbox } from "@onno/crm";`,
    `import { orders } from "./entities/Orders";`,
    `export { useRecords } from "@onno/ui";`,
    { code: `import { Truck } from "lucide-react";`, options: [{ lucide: false }] },
    { code: `import { x } from "@onno/crm/extra";`, options: [{ allow: ["@onno/crm/extra"] }] },
  ],
  invalid: [
    {
      code: `import { RecordForm } from "@onno/ui/src/components/entities/record-form";`,
      errors: [{ messageId: "privatePath", data: { source: "@onno/ui/src/components/entities/record-form", pkg: "@onno/ui", icons: " (or `@onno/ui/icons` for icons)" } }],
    },
    { code: `import { api } from "@onno/ui/src/lib/api";`, errors: [{ messageId: "privatePath" }] },
    { code: `import { CrmComposer } from "@onno/crm/CrmComposer";`, errors: [{ messageId: "privatePath" }] },
    { code: `export * from "@onno/ui/src/index";`, errors: [{ messageId: "privatePath" }] },
    { code: `const m = import("@onno/ui/src/views/app-shell");`, errors: [{ messageId: "privatePath" }] },
    { code: `import { Icon } from "../../../onno-ui/src/lib/icons";`, errors: [{ messageId: "sourceTree" }] },
    { code: `import x from "../node_modules/@onno/ui/src/index";`, errors: [{ messageId: "sourceTree" }] },
    { code: `import { Truck } from "lucide-react";`, errors: [{ messageId: "lucide" }] },
  ],
});

tester.run("icon-component", rules["icon-component"], {
  valid: [
    `import { Truck } from "@onno/ui/icons"; const orders = { icon: Truck };`,
    `const nav = { label: "Ops", icon: Activity, path: "/ops" };`,
    `const c = { icon: (row) => row.urgent ? Flame : null };`,
    `<Button icon={Truck} />;`,
    `const button = cva("btn", { variants: { size: { sm: "h-8", icon: "h-10 w-10" } } });`,
    `const o = { [icon]: "truck" };`,
  ],
  invalid: [
    {
      code: `const orders = { icon: "truck" };`,
      errors: [{ messageId: "named", data: { name: "truck", component: "Truck" } }],
    },
    {
      code: `defineApp({ pages: [{ path: "/ops", component: Ops, icon: "file-text" }] });`,
      errors: [{ messageId: "named", data: { name: "file-text", component: "FileText" } }],
    },
    { code: "const c = { \"icon\": `truck` };", errors: [{ messageId: "named" }] },
    { code: `<NavigationLink icon="truck" />;`, errors: [{ messageId: "named" }] },
    { code: `<NavigationLink icon={"arrow_right"} />;`, errors: [{ messageId: "named", data: { name: "arrow_right", component: "ArrowRight" } }] },
  ],
});

tester.run("no-raw-api-fetch", rules["no-raw-api-fetch"], {
  valid: [
    `const { rows } = useRecords("Orders", { where: { total: { gte: 100 } } });`,
    `fetch("https://example.com/api/rates");`,
    `fetch("/static/logo.svg");`,
    `fetch(url);`,
    `fetch("/apis");`,
  ],
  invalid: [
    { code: `fetch("/api/list/documents/Orders?limit=50");`, errors: [{ messageId: "rawFetch" }] },
    { code: "await fetch(`/api/catalogs/${name}/${id}`, { method: \"PUT\" });", errors: [{ messageId: "rawFetch" }] },
    { code: `window.fetch("/api/auth/me");`, errors: [{ messageId: "rawFetch" }] },
    { code: `globalThis.fetch("/api?x=1");`, errors: [{ messageId: "rawFetch" }] },
  ],
});

tester.run("notify-caught-error", rules["notify-caught-error"], {
  valid: [
    `try { await save(); } catch (failure) { notifyError(failure); }`,
    `try { await save(); } catch (failure) { toast.error("Couldn't save"); }`,
    `try { await save(); } catch { toast.error("Couldn't save"); }`,
    `if (!date) toast.error("Pick a close date");`,
    `load().catch((e) => notifyError(e, { fallback: "Could not load" }));`,
    `load().then((rows) => toast.error(rows.length));`,
    `try { a(); } catch (e) { toast.error(other.e); }`,
  ],
  invalid: [
    { code: `try { await save(); } catch (failure) { toast.error(String(failure)); }`, errors: [{ messageId: "toast", data: { name: "failure" } }] },
    { code: `try { await save(); } catch (e) { if (live) toast.error(e instanceof Error ? e.message : "Failed"); }`, errors: [{ messageId: "toast" }] },
    { code: `load().catch((err) => toast.error(err.message));`, errors: [{ messageId: "toast", data: { name: "err" } }] },
    { code: `load().catch(function (err) { toast.error(\`Couldn't load: \${err}\`); });`, errors: [{ messageId: "toast" }] },
    { code: `try { a(); } catch (outer) { try { b(); } catch (inner) { toast.error(outer.message); } }`, errors: [{ messageId: "toast", data: { name: "outer" } }] },
  ],
});

describe("@onno/ui/eslint", () => {
  it("exports an app config that enables the app-facing rules", () => {
    expect(onno.rules).toBe(rules);
    expect(configs.app.plugins?.onno).toBe(onno);
    expect(Object.keys(configs.app.rules ?? {}).sort()).toEqual([
      "onno/icon-component",
      "onno/no-private-imports",
      "onno/no-raw-api-fetch",
    ]);
  });
});
