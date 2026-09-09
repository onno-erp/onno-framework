import type { ComponentType } from "react";

/** Stable outlets supplied by the host and CRM. Applications may author additional namespaced outlets. */
export type ExtensionSlotName = "page.actions" | "entity.list.context-menu" | "entity.list.actions" | "entity.list.selection" | "entity.form.actions" | "entity.form.aside" | "crm.chat.header" | "crm.chat.composer" | "crm.chat.aside" | "crm.chat.context-menu" | "crm.contact.actions" | (string & {});
export interface ExtensionContext {
  readonly surface: "entity-list" | "entity-form" | "crm-chat" | (string & {});
  readonly kind?: string;
  readonly name?: string;
  readonly recordId?: string;
  readonly record?: Readonly<Record<string, unknown>>;
  readonly selectedIds?: readonly string[];
  readonly workspaceKey?: string;
  readonly route?: string;
  readonly actions?: readonly {id:string;label:string;enabled:boolean}[];
  readonly permissions: Readonly<Record<string, boolean>>;
  readonly refresh?: () => void | Promise<unknown>;
  readonly openRecord?: (kind: string, name: string, id: string) => void;
  /** Host preserves the draft and enforces its length limit. This never sends a message. */
  readonly insertDraft?: (text: string) => void;
  /** Only named commands supplied by this outlet are accepted. Backend authorization still applies. */
  readonly execute?: (command: string, input: Readonly<Record<string, unknown>>) => Promise<unknown>;
  readonly closeMenu?: () => void;
}
export interface ExtensionProps { context: ExtensionContext }
export interface UiExtension {
  /** Globally unique namespaced contribution ID. Registering again replaces the old contribution. */
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
