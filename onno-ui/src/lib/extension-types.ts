import type { ComponentType } from "react";

/** Stable outlets supplied by the shell and the CRM. Applications may render additional namespaced outlets. */
export type ExtensionSlotName = "shell.navigation.actions" | "entity.list.context-menu" | "entity.list.actions" | "entity.list.selection" | "entity.form.actions" | "entity.form.aside" | "crm.chat.header" | "crm.chat.composer" | "crm.chat.aside" | "crm.chat.context-menu" | "crm.contact.actions" | "crm.contact.profile" | "crm.inbox.toolbar" | (string & {});
export interface ExtensionContext {
  readonly surface: "entity-list" | "entity-form" | "crm-chat" | (string & {});
  readonly kind?: string;
  readonly name?: string;
  readonly recordId?: string;
  readonly record?: Readonly<Record<string, unknown>>;
  readonly selectedIds?: readonly string[];
  readonly workspaceKey?: string;
  readonly route?: string;
  /** The record commands the surface offers (CRM: the conversation's), with their current state. */
  readonly actions?: readonly {id:string;title:string;enabled:boolean}[];
  readonly permissions: Readonly<Record<string, boolean>>;
  readonly refresh?: () => void | Promise<unknown>;
  readonly openRecord?: (kind: string, name: string, id: string) => void;
  /** Host preserves the draft and enforces its length limit. This never sends a message. */
  readonly insertDraft?: (text: string) => void;
  /** Only named commands supplied by this outlet are accepted. Backend authorization still applies. */
  readonly execute?: (command: string, input: Readonly<Record<string, unknown>>) => Promise<unknown>;
  readonly closeMenu?: () => void;
  /**
   * How the surface is currently being read, for outlets that offer a view control rather than an
   * action. Values are the contribution's own to name and are plain strings so they survive a
   * reload or a shared link; a contribution that only acts never touches this.
   */
  readonly view?: {
    get(key: string): string | undefined;
    set(key: string, value: string | undefined): void;
  };
}
export interface ExtensionProps { context: ExtensionContext }
export interface UiExtension {
  /** A unique namespaced contribution ID (the tie-breaker for equal `order`). */
  id: string;
  slot: ExtensionSlotName;
  order?: number;
  visible?: (context: ExtensionContext) => boolean;
  component: ComponentType<ExtensionProps>;
}
export interface ExtensionSlotProps {
  name: ExtensionSlotName;
  context: ExtensionContext;
  className?: string;
}
