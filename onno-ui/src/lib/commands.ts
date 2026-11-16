import type { IconSource } from "./icons";
import type { CommandFormItem } from "../components/actions/command-form-dialog";
import type { RecordAction } from "../components/actions/record-actions";
import type { EntityRecord } from "./types";

/**
 * An entity command as the server describes it (`GET /api/commands/{kind}/{name}`, a list
 * descriptor's `commands`, a record form's `commands`): behaviour only. How a command is offered —
 * where, with which icon — is the client's business, set per entity with the `commands`
 * customization ({@link CommandCustomization}).
 */
export type CommandDescriptor = {
  key: string;
  title: string;
  description?: string;
  /** "record" runs on one record (row, record header); "collection" on the entity (list toolbar). */
  target: "record" | "collection";
  /** Commands sharing a group gather under one submenu labelled with it. */
  group?: string;
  /** Inputs the command collects in a dialog before it runs. */
  form?: CommandFormItem[];
  /** The dialog's opening values come from `GET …/{key}/defaults` per open. */
  dynamicForm?: boolean;
  /** A record form's command: whether it may run on that record now (absent = yes). */
  enabled?: boolean;
};

/** A command's state on one record, as the list feed attaches it to each row under `_actions`. */
export type CommandState = { visible?: boolean; enabled?: boolean; title?: string };

/**
 * Where a command is offered:
 * - `"primary"`: a prominent record-header button (a collection command: a prominent toolbar button);
 * - `"menu"`: the record header's overflow menu and the list row's context menu (the default for
 *   record commands);
 * - `"row"`: an inline icon button on every list row, and the menus;
 * - `"hidden"`: not offered (still runnable through the API and `useCommand`).
 */
export type CommandPlacement = "primary" | "menu" | "row" | "hidden";

/** How the client offers one command. `icon` may vary per record (e.g. pause / play). */
export type CommandPresentation = {
  placement?: CommandPlacement;
  /** A lucide icon name, or a function of the record (list rows only; elsewhere it gets the open record). */
  icon?: IconSource | ((record: EntityRecord) => IconSource | null | undefined);
  /** Replaces the server's title. */
  title?: string;
};

/** A presentation, or just its placement: `{ advance: "primary" }`. */
export type CommandCustomization = CommandPresentation | CommandPlacement;

/** The built-in record operations a `commands` customization also places. */
export const BUILT_IN_COMMANDS = ["post", "unpost", "duplicate", "delete", "openInList"] as const;

/**
 * A record action as the server sends it (a record form's `actions`): an operation the viewer may
 * perform on the record now — a built-in (`unpost`, `duplicate`, `delete`, `openInList`) or an
 * extension module's (`print-invoice`, which carries its `title`). Data only: the client names,
 * icons and places it by `key`.
 */
export type RecordActionDescriptor = {
  key: string;
  url: string;
  /** Set for contributed actions; the built-ins are named by the client's messages. */
  title?: string;
  /** The action destroys data (delete). */
  danger?: boolean;
};

/** The icon each built-in record action shows unless the `commands` customization names one. */
export const BUILT_IN_ACTION_ICONS: Record<string, string> = {
  post: "circle-check",
  unpost: "rotate-ccw",
  duplicate: "copy",
  delete: "trash-2",
  openInList: "list",
};

/** The icon a command shows when nothing names one. */
export const DEFAULT_COMMAND_ICON = "circle-dot";

/** A command with its presentation resolved. */
export type PresentedCommand = CommandDescriptor & {
  placement: CommandPlacement;
  icon?: IconSource | ((record: EntityRecord) => IconSource | null | undefined);
};

export function normalizePresentation(c: CommandCustomization | undefined): CommandPresentation {
  if (!c) return {};
  return typeof c === "string" ? { placement: c } : c;
}

/** Merge presentation maps; later maps win per key and per property. */
export function mergePresentations(
  ...maps: (Record<string, CommandCustomization> | undefined)[]
): Record<string, CommandPresentation> {
  const out: Record<string, CommandPresentation> = {};
  for (const m of maps) {
    if (!m) continue;
    for (const [k, v] of Object.entries(m)) out[k] = { ...out[k], ...normalizePresentation(v) };
  }
  return out;
}

/** Resolve each descriptor's presentation: the customization's, else the defaults. */
export function presentCommands(
  commands: CommandDescriptor[] | undefined,
  presentation: Record<string, CommandCustomization> | undefined,
): PresentedCommand[] {
  return (commands ?? []).map((c) => {
    const p = normalizePresentation(presentation?.[c.key]);
    return { ...c, title: p.title || c.title, placement: p.placement ?? "menu", icon: p.icon };
  });
}

/** The icon to draw for a command on `record` (a per-record function falls back on failure). */
export function commandIcon(command: { icon?: PresentedCommand["icon"] }, record?: EntityRecord | null): IconSource {
  const icon = command.icon;
  if (typeof icon === "function") {
    try {
      return (record ? icon(record) : null) || DEFAULT_COMMAND_ICON;
    } catch {
      return DEFAULT_COMMAND_ICON;
    }
  }
  return icon || DEFAULT_COMMAND_ICON;
}

/**
 * The record actions as header items: named (`title`, else the built-in's message), iconed and
 * placed by the `commands` customization (default: the overflow menu, the built-in's icon). A
 * header has no rows, so a `"row"` placement means the menu; `"hidden"` drops the action.
 */
export function presentRecordActions(
  actions: RecordActionDescriptor[] | undefined,
  presentation: Record<string, CommandCustomization> | undefined,
  builtInTitle: (key: string) => string,
): RecordAction[] {
  const out: RecordAction[] = [];
  for (const a of actions ?? []) {
    const p = normalizePresentation(presentation?.[a.key]);
    if (p.placement === "hidden") continue;
    const placement = p.placement === "primary" ? "primary" : "menu";
    out.push({
      key: a.key,
      url: a.url,
      label: p.title || a.title || builtInTitle(a.key),
      icon: p.icon && typeof p.icon !== "function" ? p.icon : BUILT_IN_ACTION_ICONS[a.key] ?? DEFAULT_COMMAND_ICON,
      tone: a.danger ? "danger" : placement === "primary" ? "accent" : "normal",
      placement,
    });
  }
  return out;
}
