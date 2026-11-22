import { navigationLinks } from "./navigation-tree";
import type { IconSource } from "./icons";
import type { Branding } from "./types";
import type { ShellData, ShellNavSection } from "./view-types";

/**
 * The business model as the signed-in user sees it (`GET /api/ui/model`): every readable catalog,
 * document and register with its route, title, bounded context and the user's rights, the readable
 * information registers and (with `settings.edit`) the settings, plus the user. The client builds
 * navigation and default screens from this. Branding is not part of it: it comes from
 * `GET /api/branding` ({@link useBranding}), the one place the server serves it.
 */
export type ModelEntity = {
  kind: "catalogs" | "documents" | "registers";
  /** Route segment (snake_case logical name). */
  name: string;
  logicalName: string;
  title: string;
  context: string;
  route: string;
  canWrite: boolean;
  canPost?: boolean;
  postable?: boolean;
  hierarchical?: boolean;
  balance?: boolean;
  /** A catalog or document that records its change history (`history = true`). */
  history?: boolean;
};

export type AppModel = {
  user: {
    username: string;
    displayName: string;
    avatarUrl: string;
    /** The user's roles, normalized (upper case, no `ROLE_` prefix). */
    roles: string[];
    /** The permission keys the user's roles are granted (`"settings.edit"`, …). */
    permissions: string[];
    /** True when the app declares no role grants: every user holds every permission. */
    unrestricted: boolean;
  };
  /** Every role the app declares (its `RoleKey` enum), normalized; empty without role grants. */
  roles: string[];
  /** `periodClose` when period close is configured (the `/api/period-close` endpoints exist). */
  features: { comments: boolean; notifications: boolean; periodClose?: boolean };
  entities: ModelEntity[];
  /** The readable information registers (read with `useInformationRegister`). */
  informationRegisters: ModelInformationRegister[];
  /** The `@Constant` settings and their Java value type — only with `settings.edit` (empty otherwise). */
  constants: { name: string; type: string }[];
};

/**
 * The framework's one role-name normalization, as on the server: trim, upper-case and strip a
 * `ROLE_` prefix (`"ROLE_manager"` → `"MANAGER"`).
 */
export function normalizeRole(role: string): string {
  const upper = role.trim().toUpperCase();
  return upper.startsWith("ROLE_") ? upper.slice("ROLE_".length) : upper;
}

/** Whether the user holds any of `roles` (compared normalized); true for an empty or absent list. */
export function holdsRole(model: AppModel, roles: readonly string[] | undefined): boolean {
  if (!roles?.length) return true;
  const held = new Set(model.user.roles.map(normalizeRole));
  return roles.some((r) => held.has(normalizeRole(r)));
}

/**
 * Whether the user holds `permission`; true when none is required. In an app without role grants
 * every permission is held — except the few the server only honours when granted explicitly
 * (`periodClose.override`, `processes.administer`, `crm.administer`), which it checks itself.
 */
export function holdsPermission(model: AppModel, permission: string | undefined): boolean {
  if (!permission) return true;
  return model.user.unrestricted || model.user.permissions.includes(permission);
}

/** An information register in the model. */
export type ModelInformationRegister = { logicalName: string; title: string; context: string; periodic: boolean };

const KIND_SECTION: Record<ModelEntity["kind"], string> = {
  catalogs: "Catalogs",
  documents: "Documents",
  registers: "Reports",
};

/** An entity's icon when its customization names none: one per model kind. */
export const ENTITY_KIND_ICONS: Record<ModelEntity["kind"], string> = {
  catalogs: "book-marked",
  documents: "file-text",
  registers: "chart-column",
};

/**
 * The zero-configuration navigation: the given page links first, then one section per bounded
 * context (ungrouped entities under their model kind). `iconOf` picks each entity's icon (the app's
 * customization, see `entityIcon`); the default is the kind's.
 */
export function defaultNavigation(
  model: AppModel,
  links: ShellNavSection["items"] = [],
  iconOf: (entity: ModelEntity) => IconSource = (e) => ENTITY_KIND_ICONS[e.kind],
): ShellNavSection[] {
  const sections = new Map<string, ShellNavSection>();
  for (const e of model.entities) {
    const title = e.context || KIND_SECTION[e.kind];
    let s = sections.get(title);
    if (!s) sections.set(title, (s = { title, items: [] }));
    s.items.push({ label: e.title, icon: iconOf(e), path: e.route });
  }
  return [...(links.length ? [{ title: null, items: links }] : []), ...sections.values()];
}

/** What the shell's chrome shows of the app's branding, for one colour mode. */
export type ShellBrand = Pick<ShellData, "brand" | "logo" | "logoWidth" | "logoHeight" | "mark" | "markFramed">;

/**
 * The configured branding as the chrome shows it in `mode`: the dark logo/mark in dark mode when
 * set, else the light one. A missing mark falls back to the (square) favicon, then to the logo.
 */
export function shellBrand(branding: Branding, mode: "light" | "dark"): ShellBrand {
  const pick = (light?: string | null, dark?: string | null) => (mode === "dark" && dark) || light || "";
  const logo = pick(branding.logoUrl, branding.logoUrlDark);
  return {
    brand: branding.appName || "onno",
    logo,
    logoWidth: branding.logoWidth,
    logoHeight: branding.logoHeight,
    mark: pick(branding.markUrl, branding.markUrlDark) || branding.faviconUrl || logo,
    markFramed: branding.markFramed ?? true,
  };
}

/** The shell the chrome renders: the branding and the model's account plus the given navigation. */
export function shellFromModel(
  model: AppModel,
  brand: ShellBrand,
  navStyle: ShellData["navStyle"],
  hasHome: boolean,
  navigation: ShellNavSection[] = defaultNavigation(model),
): ShellData {
  const titles: Record<string, string> = {};
  const icons: Record<string, IconSource> = {};
  for (const s of navigation) {
    for (const item of navigationLinks(s.items)) {
      titles[item.path] ??= item.label;
      if (item.icon) icons[item.path] ??= item.icon;
    }
  }
  const first = navigationLinks(navigation.flatMap((s) => s.items))[0]?.path ?? "/";
  return {
    navStyle,
    home: hasHome ? "/" : first,
    ...brand,
    navigation,
    notifications: model.features.notifications,
    accountInfo: {
      displayName: model.user.displayName,
      avatarUrl: model.user.avatarUrl,
    },
    titles,
    icons,
    mainRecordRoutes: [],
  };
}

/** Whether a route is one of the model's default entity surfaces (served by `/api/ui/view`). */
export function isEntitySurface(path: string): boolean {
  const seg = path.split("?")[0].split("/").filter(Boolean);
  return seg.length >= 2 && (seg[0] === "catalogs" || seg[0] === "documents" || seg[0] === "registers");
}
