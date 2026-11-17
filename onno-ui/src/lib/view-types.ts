import type { ReactNode } from "react";
import type { IconSource } from "./icons";
/**
 * The view API contract (`GET /api/ui/view/**`): an entity's default surface, as data — the
 * descriptor of its list, record form or register report. The client draws the page around it
 * (padding, title, the comments thread).
 */
export type ViewModel =
  | { list: Record<string, unknown> }
  | { form: Record<string, unknown> }
  | { register: Record<string, unknown> };

/** One block a default surface renders; `type` selects the renderer (see `route-view.tsx`). */
export type ViewNode =
  | { type: "list"; list: Record<string, unknown> }
  | { type: "form"; form: Record<string, unknown> }
  | { type: "register"; register: Record<string, unknown> }
  | { type: "comments"; target: { kind: string; name: string; id: string } };

export type ShellNavItem = { label: string; icon?: IconSource; path: string; mobileLabel?: string; mobileOrder?: number };
export type ShellNavGroup = { title: string; icon?: IconSource | null; actions?: ReactNode; items: ShellNavNode[]; path?: never; label?: never };
export type ShellNavNode = ShellNavItem | ShellNavGroup;
export type ShellNavSection = { title?: string | null; icon?: IconSource | null; actions?: ReactNode; items: ShellNavNode[] };

export type NavStyle = "sidebar" | "bottom_bar";

/**
 * The shell: navigation, branding and account. Built client-side from the model
 * (`GET /api/ui/model`, already RBAC-filtered) by `shellFromModel`.
 */
export type ShellData = {
  navStyle: NavStyle;
  /** Where "/" lands: the dashboard when there is one, otherwise the first real nav item. */
  home: string;
  brand?: string;
  logo?: string;
  logoWidth?: number | null;
  logoHeight?: number | null;
  mark?: string;
  markFramed?: boolean;
  navigation: ShellNavSection[];
  notifications?: boolean;
  accountInfo?: {
    displayName: string;
    avatarUrl?: string;
  };
  titles?: Record<string, string>;
  icons?: Record<string, IconSource>;
  mainRecordRoutes?: string[];
};
