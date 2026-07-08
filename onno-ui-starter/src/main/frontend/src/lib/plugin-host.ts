import * as React from "react";
import * as ReactJSXRuntime from "react/jsx-runtime";
import htm from "htm";
import { registerWidget } from "./widget-bridge";
import { api } from "./api";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { Checkbox } from "@/components/ui/checkbox";
import { Switch } from "@/components/ui/switch";
import { Segmented } from "@/components/ui/segmented";
import { DatePicker } from "@/components/date-picker";
import { Card, CardHeader, CardTitle, CardDescription, CardContent } from "@/components/ui/card";
import { Popover, PopoverTrigger, PopoverContent } from "@/components/ui/popover";
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";

/**
 * The host globals a consumer's custom-widget plugin binds against at runtime.
 *
 * Plugins are separate ESM modules loaded at boot (see {@link ./plugin-loader}); they are NOT
 * bundled with the SPA. To keep them tiny and — crucially — to make React hooks and context work,
 * a plugin must reuse the *host's single React instance* rather than bundling its own. So we hang
 * that instance (plus the JSX runtime, the widget registry, an html tagged-template, and a
 * read-only data client) on `window.onno`. The `@onno/widget-sdk` package's `react` / `react/jsx-
 * runtime` shims resolve to these fields, so a plugin authored with normal `import ... from "react"`
 * + automatic JSX ships with zero React inside it.
 */
/**
 * The host's UI primitives, re-exposed so a custom widget renders the *real* design-system controls
 * (Radix-backed Select/Popover, the app's Button/Segmented/Badge/…) instead of hand-rolled
 * lookalikes. Because these are the host's own components — carrying the host's Tailwind classes,
 * already emitted into the host stylesheet — a widget sidesteps the class-emission gotcha (utilities
 * the host doesn't itself emit produce no CSS when a widget is compiled outside the host build) and
 * never drifts from the product's look. Curated subset; grow it as widgets need more (issue #243).
 */
export interface OnnoUi {
  Button: typeof Button;
  Badge: typeof Badge;
  Input: typeof Input;
  Label: typeof Label;
  Textarea: typeof Textarea;
  Checkbox: typeof Checkbox;
  Switch: typeof Switch;
  Segmented: typeof Segmented;
  DatePicker: typeof DatePicker;
  Card: typeof Card;
  CardHeader: typeof CardHeader;
  CardTitle: typeof CardTitle;
  CardDescription: typeof CardDescription;
  CardContent: typeof CardContent;
  Popover: typeof Popover;
  PopoverTrigger: typeof PopoverTrigger;
  PopoverContent: typeof PopoverContent;
  Select: typeof Select;
  SelectContent: typeof SelectContent;
  SelectGroup: typeof SelectGroup;
  SelectItem: typeof SelectItem;
  SelectTrigger: typeof SelectTrigger;
  SelectValue: typeof SelectValue;
}

export interface OnnoHost {
  /** The host's React — a plugin's components must use this exact instance (shared hooks/context). */
  React: typeof React;
  /** The host's automatic-JSX runtime (`react/jsx-runtime`), for the SDK's jsx-runtime shim. */
  jsxRuntime: typeof ReactJSXRuntime;
  /** Register (or override) the renderer for a widget type; see {@link registerWidget}. */
  registerWidget: typeof registerWidget;
  /** `htm` bound to `React.createElement` — JSX-like authoring with no build step (Tier-0). */
  html: (strings: TemplateStringsArray, ...values: unknown[]) => unknown;
  /** Read-only slice of the REST client — safe surface for first-party widgets. */
  api: OnnoReadApi;
  /** The host's UI component primitives (see {@link OnnoUi}) — the real design-system controls. */
  ui: OnnoUi;
  /** Host contract version; bump on a breaking change to this shape. */
  version: number;
}

/**
 * The read-only subset of {@link api} exposed to plugins. Deliberately excludes create/update/
 * delete/post: a dashboard widget reads and renders; it does not mutate. (First-party plugins run
 * with the app's full session, so this is least-privilege by convention, not a hard sandbox.)
 */
export type OnnoReadApi = Pick<
  typeof api,
  | "listCatalog"
  | "searchCatalog"
  | "getCatalogItem"
  | "listDocuments"
  | "searchDocument"
  | "getDocument"
  | "getBalance"
  | "getTurnover"
  | "getMovements"
>;

declare global {
  interface Window {
    onno?: OnnoHost;
  }
}

// The curated UI primitives handed to widgets. Same instances the host renders with, so a widget's
// controls are the product's controls — see {@link OnnoUi}.
const ui: OnnoUi = {
  Button,
  Badge,
  Input,
  Label,
  Textarea,
  Checkbox,
  Switch,
  Segmented,
  DatePicker,
  Card,
  CardHeader,
  CardTitle,
  CardDescription,
  CardContent,
  Popover,
  PopoverTrigger,
  PopoverContent,
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
};

const readApi: OnnoReadApi = {
  listCatalog: api.listCatalog,
  searchCatalog: api.searchCatalog,
  getCatalogItem: api.getCatalogItem,
  listDocuments: api.listDocuments,
  searchDocument: api.searchDocument,
  getDocument: api.getDocument,
  getBalance: api.getBalance,
  getTurnover: api.getTurnover,
  getMovements: api.getMovements,
};

/**
 * Install `window.onno` once, before any plugin loads. Idempotent — a second call is a no-op so
 * re-imports (HMR, test doubles) don't swap the instance out from under already-loaded plugins.
 */
export function installPluginHost(): OnnoHost {
  if (window.onno) return window.onno;
  const host: OnnoHost = Object.freeze({
    React,
    jsxRuntime: ReactJSXRuntime,
    registerWidget,
    html: htm.bind(React.createElement) as OnnoHost["html"],
    api: readApi,
    ui,
    // v2: added `ui` (host UI primitives). Additive — existing widgets keep working.
    version: 2,
  });
  window.onno = host;
  return host;
}

// Install on import so the host is present as early as the module graph is evaluated — the loader
// (and any plugin it pulls in) can rely on `window.onno` existing.
installPluginHost();
