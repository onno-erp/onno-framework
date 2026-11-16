import { DialogShell } from "@/components/ui/dialog-shell";
import { ResponsiveActionPanel } from "@/components/ui/responsive-action-panel";
import { WorkspaceToolbar } from "@/components/ui/workspace-toolbar";
import { usePageSlide } from "@/lib/use-page-slide";
import { extensions } from "./ui-extensions";
import { CommentComposer, CommentBody } from "@/components/entity-comments-widget";
import { EntityTagMenu, EntityTags } from "@/components/entity-tags";
import { ContextMenuSub, ContextMenuContent, ContextMenuItem } from "@/components/ui/context-menu";
import { chatMessages } from "./chat-message-renderers";
import { EntityListWidget, OptionsFacet } from "@/components/entity-list-widget";
import { toast } from "@/components/ui/toast";
import * as React from "react";
import * as ReactJSXRuntime from "react/jsx-runtime";
import htm from "htm";
import { registerWidget } from "./widget-bridge";
import { subscribeUiEvents } from "./ui-event-bus";
import { widgetNavigation } from "./widget-navigation";
import { subscribeWidgetMessages, widgetLocale, widgetTranslate } from "./widget-messages";
import { registerListRenderer, registerListSelection } from "./widget-registry";
import type { Translate } from "@/lib/messages";
import { api } from "./api";
import type { UiEvent, UiEventFilter } from "./types";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { Checkbox } from "@/components/ui/checkbox";
import { Switch } from "@/components/ui/switch";
import { Segmented } from "@/components/ui/segmented";
import { DatePicker } from "@/components/date-picker";
import { DateTimePicker } from "@/components/date-time-picker";
import { ColorPicker } from "@/components/color-picker";
import { RefSelect } from "@/components/ref-select";
import { Card, CardHeader, CardTitle, CardDescription, CardContent } from "@/components/ui/card";
import { Popover, PopoverAnchor, PopoverTrigger, PopoverContent } from "@/components/ui/popover";
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
  WorkspaceToolbar: typeof WorkspaceToolbar;
  usePageSlide: typeof usePageSlide;
  ContextMenuContent: typeof ContextMenuContent;
  ContextMenuItem: typeof ContextMenuItem;
  EntityTagMenu: typeof EntityTagMenu;
  ContextMenuSub: typeof ContextMenuSub;
  EntityTags: typeof EntityTags;
  Button: typeof Button;
  Badge: typeof Badge;
  Input: typeof Input;
  Label: typeof Label;
  Textarea: typeof Textarea;
  CommentComposer: typeof CommentComposer;
  CommentBody: typeof CommentBody;
  EntityListWidget: typeof EntityListWidget;
  OptionsFacet: typeof OptionsFacet;
  Checkbox: typeof Checkbox;
  Switch: typeof Switch;
  Segmented: typeof Segmented;
  DatePicker: typeof DatePicker;
  DateTimePicker: typeof DateTimePicker;
  ColorPicker: typeof ColorPicker;
  /**
   * The reference-field picker: server-side typeahead, recent picks, "+ New" and "Show all…" (the
   * target's full list in a picker dialog). Added in host contract v7.
   */
  RefSelect: typeof RefSelect;
  Card: typeof Card;
  CardHeader: typeof CardHeader;
  CardTitle: typeof CardTitle;
  CardDescription: typeof CardDescription;
  CardContent: typeof CardContent;
  Popover: typeof Popover;
  PopoverAnchor: typeof PopoverAnchor;
  PopoverTrigger: typeof PopoverTrigger;
  DialogShell: typeof DialogShell;
  ResponsiveActionPanel: typeof ResponsiveActionPanel;
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
  registerListRenderer: typeof registerListRenderer;
  registerListSelection: typeof registerListSelection;
  /** `htm` bound to `React.createElement` — JSX-like authoring with no build step (Tier-0). */
  html: (strings: TemplateStringsArray, ...values: unknown[]) => unknown;
  /** Read-only slice of the REST client — safe surface for first-party widgets. */
  api: OnnoReadApi;
  /** Shared notification manager for widget action feedback. */
  toast: Pick<typeof toast, "success" | "error" | "info" | "warning">;
  /** In-app navigation through the workspace router; added in host contract v8. */
  navigation: typeof widgetNavigation;
  /** The host's UI component primitives (see {@link OnnoUi}) — the real design-system controls. */
  ui: OnnoUi;
  /** Shared live updates; added in host contract v3. */
  events: {
    subscribe(listener: (event: UiEvent) => void, filter?: UiEventFilter): () => void;
  };
  /**
   * The app's chrome strings, so a widget's own labels localize with the rest of the shell instead
   * of hardcoding English. {@code get()} returns the current translate function and {@code
   * subscribe} fires when the server's `onno.ui.messages` overlay arrives; added in contract v6.
   * {@code locale()} is {@code onno.ui.locale} for formatting dates and numbers (undefined: the
   * browser's), announced through the same {@code subscribe}; added in contract v7.
   */
  messages: {
    get(): Translate;
    subscribe(listener: () => void): () => void;
    locale(): string | undefined;
  };
  /** Host contract version; bump on a breaking change to this shape. */
  chatMessages: typeof chatMessages;
  extensions: typeof extensions;
  version: number;
}

function eventMatchesFilter(event: UiEvent, filter?: UiEventFilter): boolean {
  if (!filter) return true;
  if (filter.types && !filter.types.includes(event.type)) return false;
  if (filter.entityType !== undefined && event.entityType !== filter.entityType) return false;
  if (filter.entityName !== undefined && event.entityName !== filter.entityName) return false;
  return filter.id === undefined || event.id === filter.id;
}

const events: OnnoHost["events"] = Object.freeze({
  subscribe: (listener, filter) =>
    subscribeUiEvents((event) => {
      if (eventMatchesFilter(event, filter)) listener(event);
    }),
});

/**
 * The read-only subset of {@link api} exposed to plugins. Deliberately excludes create/update/
 * delete/post: a dashboard widget reads and renders; it does not mutate. (First-party plugins run
 * with the app's full session, so this is least-privilege by convention, not a hard sandbox.)
 */
export type OnnoReadApi = Pick<
  typeof api,
  | "getJson"
  | "listCatalog"
  | "listCatalogPage"
  | "getCatalogItem"
  | "listDocuments"
  | "listDocumentsPage"
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
  WorkspaceToolbar,
  usePageSlide,
  EntityTagMenu,
  ContextMenuSub,
  EntityTags,
  ContextMenuContent,
  ContextMenuItem,
  EntityListWidget,
  OptionsFacet,
  Button,
  Badge,
  Input,
  Label,
  Textarea,
  CommentComposer,
  CommentBody,
  Checkbox,
  Switch,
  Segmented,
  DatePicker,
  DateTimePicker,
  ColorPicker,
  RefSelect,
  Card,
  CardHeader,
  CardTitle,
  CardDescription,
  CardContent,
  Popover,
  PopoverAnchor,
  PopoverTrigger,
  DialogShell,
  ResponsiveActionPanel,
  PopoverContent,
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
};

/** A deep copy of a JSON response; structuredClone where available, a JSON round-trip otherwise. */
function detachedCopy<T>(value: T): T {
  if (value === undefined || value === null) return value;
  return typeof structuredClone === "function" ? structuredClone(value) : (JSON.parse(JSON.stringify(value)) as T);
}

/**
 * Host reads share in-flight GET promises, so the resolved object can be the very one the host is
 * rendering. Plugins get their own deep copy: a widget that mutates a response must not corrupt host
 * state (or another widget's).
 */
function detached<A extends unknown[], R>(read: (...args: A) => Promise<R>): (...args: A) => Promise<R> {
  return (...args) => read(...args).then(detachedCopy);
}

const readApi: OnnoReadApi = {
  getJson: detached(api.getJson) as OnnoReadApi["getJson"],
  listCatalog: detached(api.listCatalog),
  listCatalogPage: detached(api.listCatalogPage),
  getCatalogItem: detached(api.getCatalogItem),
  listDocuments: detached(api.listDocuments),
  listDocumentsPage: detached(api.listDocumentsPage),
  getDocument: detached(api.getDocument),
  getBalance: detached(api.getBalance),
  getTurnover: detached(api.getTurnover),
  getMovements: detached(api.getMovements),
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
    registerListRenderer,
    registerListSelection,
    html: htm.bind(React.createElement) as OnnoHost["html"],
    api: readApi,
    toast,
    navigation: widgetNavigation,
    ui,
    events,
    // v6: widgets can translate their own labels. Additive — existing widgets keep working.
    // v7: and the configured locale, so widgets format dates like the shell.
    messages: Object.freeze({ get: widgetTranslate, subscribe: subscribeWidgetMessages, locale: widgetLocale }),
    // v4: added custom chat body renderers. Additive — existing widgets keep working.
    chatMessages,
    extensions,
    version: 8,
  });
  window.onno = host;
  return host;
}

// Install on import so the host is present as early as the module graph is evaluated — the loader
// (and any plugin it pulls in) can rely on `window.onno` existing.
installPluginHost();
