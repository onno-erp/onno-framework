import type { IconSource } from "./icons";
import { createContext, useContext, useEffect, useMemo, useState, type ComponentType, type ReactNode } from "react";
import { defaultNavigation, ENTITY_KIND_ICONS, isEntitySurface, type AppModel, type ModelEntity } from "./app-model";
import { applyForm, applyList, customizationsFor, type EntityCustomization } from "./entity-customizations";
import { findEntity, modelOutsideReact, useModel } from "./model-store";
import { openRoute } from "./navigation";
import { matchRoute, route, type MatchedRoute, type Route, type RouteProps } from "./routes";
import type { ShellNavSection } from "./view-types";
import type { ListDescriptor } from "../components/lists/entity-list";
import { RecordForm, type RecordFormState, type FormDescriptor } from "../components/entities/record-form";
import type { NavigationProps } from "../components/shell/navigation";
import type { UiExtension } from "./extension-types";
import type { LoginPageProps } from "../components/auth/sign-in";

/**
 * The whole app UI as one declaration: its pages, its navigation and how each entity's default
 * screens differ from the framework's. Everything is optional — `{}` is the zero-configuration
 * app. The shell receives it at the root ({@link AppConfigProvider}) and every part of the UI
 * reads it from there: no registration, no module state.
 *
 *   defineApp({
 *     pages: [
 *       { path: "/", component: HomePage },
 *       { list: "Orders", component: OrdersPage, icon: ClipboardList },
 *       { record: "Orders", component: OrderPage },
 *       { path: "/settings", component: SettingsPage, label: "Settings", icon: Settings, roles: ["ADMIN"] },
 *     ],
 *     navigation: [
 *       { title: "Sales", icon: ShoppingCart, items: ["Orders", "Customers"] },
 *       { title: "System", icon: Settings, items: ["/settings"] },
 *     ],
 *   });
 */
export type PageDefinition = {
  /** The route, e.g. `"/ops"`; an entity route (`"/catalogs/suppliers"`) replaces its default screen. */
  path?: string;
  list?: string;
  record?: string;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any -- a page receives props for its kind of route
  component: ComponentType<any>;
  /** Navigation label and icon, used wherever the navigation lists this page. */
  label?: string;
  icon?: IconSource;
  /** Only users holding one of these roles see the page and its nav link (`ADMIN` always does). */
  roles?: string[];
  /** A further condition, e.g. an optional module being present. May be async. */
  when?: (model: AppModel) => boolean | Promise<boolean>;
  /**
   * Fetch the server's default view for this route and pass it as `view`/`renderDefault`. Defaults
   * to true on entity routes and false elsewhere.
   */
  view?: boolean;
};

/** An entity by logical name (`"Orders"`), a page by path (`"/ops"`), or either with overrides. */
export type NavItemSpec =
  | string
  | { entity?: string; path?: string; label?: string; icon?: IconSource; roles?: string[] }
  | NavGroupSpec;

export type NavGroupSpec = { title: string; icon?: IconSource; actions?: ReactNode; roles?: string[]; items: NavItemSpec[] };
export type NavSectionSpec = { title?: string | null; icon?: IconSource; actions?: ReactNode; roles?: string[]; items: NavItemSpec[] };

export type AppDefinition = {
  navigationComponent?: ComponentType<NavigationProps>;
  entities?: Record<string, EntityCustomization | undefined>;
  pages?: PageDefinition[];
  /**
   * The sidebar. Omitted: entities grouped by context, plus every labelled page. A list of sections
   * names entities and pages; anything the user cannot read or use is dropped, as are empty sections.
   */
  navigation?: NavSectionSpec[] | ((model: AppModel, defaults: ShellNavSection[]) => ShellNavSection[]);
  /** Components contributed to the UI's named outlets ({@link ExtensionSlot}s). */
  extensions?: UiExtension[];
  /** The app's own login page, replacing the framework's (`/login`). */
  login?: ComponentType<LoginPageProps>;
};

type NavItem = ShellNavSection["items"][number];

function hasRole(model: AppModel, roles: string[] | undefined): boolean {
  if (!roles?.length) return true;
  return model.user.roles.includes("ADMIN") || roles.some((r) => model.user.roles.includes(r));
}

/** The resolved app configuration every part of the shell reads. */
export type AppConfig = {
  app: AppDefinition;
  /** The app's pages as routes (plus any the shell adds, e.g. the mobile menu). */
  routes: Route[];
  /** Whether the signed-in user may use a page: its roles and its (possibly async) condition. */
  pageAllowed: (page: PageDefinition) => boolean;
};

const EMPTY: AppConfig = { app: {}, routes: [], pageAllowed: () => true };
const AppConfigContext = createContext<AppConfig>(EMPTY);

/** Resolve a registration through the readable model. Missing optional entities have no route. */
export function pagePath(page: PageDefinition, model: AppModel | null): string | null {
  if (page.path) return page.path;
  const entity = findEntity(model, page.list ?? page.record ?? "");
  return entity ? entity.route + (page.record ? "/:id" : "") : null;
}

/** The page component behind its `roles`/`when` gate. */
function gated(page: PageDefinition): ComponentType<RouteProps> {
  const Page = page.component;
  function GatedPage(props: RouteProps) {
    const { pageAllowed } = useContext(AppConfigContext);
    if (!pageAllowed(page)) {
      return <p className="p-4 text-sm text-muted-foreground">This page is not available.</p>;
    }
    if (page.record) {
      const descriptor = props.view && "form" in props.view ? props.view.form as unknown as FormDescriptor : null;
      return descriptor ? <RecordForm form={descriptor} component={Page as ComponentType<{ form: RecordFormState }>} /> : null;
    }
    if (page.list) {
      const list = props.view && "list" in props.view ? props.view.list as unknown as ListDescriptor : null;
      return list ? <Page {...props} list={list} /> : null;
    }
    return <Page {...props} />;
  }
  return GatedPage;
}

/**
 * Hands the app's declaration to the UI below. Pages become routes; async page conditions (`when`)
 * resolve once the model is in, and the pages they guard appear when they answer yes.
 */
export function AppConfigProvider({ app, children }: { app: AppDefinition; children: ReactNode }) {
  const model = useModel();
  const pages = app.pages;
  const routes = useMemo(
    () => (pages ?? []).flatMap((p) => {
      const path = pagePath(p, model);
      if (!path) return [];
      const component = gated(p);
      const options = { clientOnly: !(p.view ?? isEntitySurface(path)) };
      return [route(path, component, options), ...(p.record ? [route(path + "/duplicate", component, options)] : [])];
    }),
    [pages, model],
  );
  const [answers, setAnswers] = useState<ReadonlyMap<PageDefinition, boolean>>(new Map());
  useEffect(() => {
    if (!model) return;
    let cancelled = false;
    for (const page of pages ?? []) {
      if (!page.when) continue;
      void Promise.resolve()
        .then(() => page.when!(model))
        .then((ok) => ok, () => false)
        .then((ok) => {
          if (!cancelled) setAnswers((prev) => new Map(prev).set(page, ok));
        });
    }
    return () => {
      cancelled = true;
    };
  }, [model, pages]);
  const value = useMemo<AppConfig>(() => ({
    app,
    routes,
    pageAllowed: (page) => !!model && hasRole(model, page.roles) && (!page.when || answers.get(page) === true),
  }), [app, routes, model, answers]);
  return <AppConfigContext.Provider value={value}>{children}</AppConfigContext.Provider>;
}

/** Adds routes the shell itself serves (the mobile menu) to the app's, for the tree below. */
export function ShellRoutes({ routes, children }: { routes: Route[]; children: ReactNode }) {
  const parent = useContext(AppConfigContext);
  const value = useMemo(() => ({ ...parent, routes: [...routes, ...parent.routes] }), [parent, routes]);
  return <AppConfigContext.Provider value={value}>{children}</AppConfigContext.Provider>;
}

export function useAppConfig(): AppConfig {
  return useContext(AppConfigContext);
}

/** The app page for a path, or null when the path is the framework's default screen. */
export function usePageRoute(path: string): MatchedRoute | null {
  const { routes } = useAppConfig();
  return useMemo(() => matchRoute(routes, path), [routes, path]);
}

/** The customizations the app declared for an entity, plus any passed explicitly. */
export function useEntityCustomizations(
  kind: string,
  name: string,
  extra?: EntityCustomization,
): EntityCustomization[] {
  const { app } = useAppConfig();
  const model = useModel();
  return useMemo(() => {
    const logicalName = findEntity(model, `${kind}/${name}`)?.logicalName;
    const declared = customizationsFor(app.entities, { kind, name, logicalName });
    return extra ? [...declared, extra] : declared;
  }, [app.entities, model, kind, name, extra]);
}

/** An entity's default list with the app's customizations (and `extra`) applied. */
export function useCustomizedList(list: ListDescriptor | null, extra?: EntityCustomization): ListDescriptor | null {
  const customizations = useEntityCustomizations(list?.kind ?? "", list?.name ?? "", extra);
  return useMemo(() => (list ? applyList(list, customizations) : null), [list, customizations]);
}

/** An entity's default form with the app's customizations applied. */
export function useCustomizedForm(form: FormDescriptor): FormDescriptor {
  const customizations = useEntityCustomizations(form.kind, form.name);
  return useMemo(() => applyForm(form, customizations), [form, customizations]);
}

/** An entity's icon: its customization's `icon`, else the default for its kind. */
export function entityIcon(app: AppDefinition, entity: ModelEntity): IconSource {
  return app.pages?.find((p) => p.list === entity.logicalName && p.icon)?.icon ?? customizationsFor(app.entities, entity).find((c) => c.icon)?.icon ?? ENTITY_KIND_ICONS[entity.kind];
}

/** The navigation for the signed-in user. */
export function useNavigation(model: AppModel | null): ShellNavSection[] | null {
  const { app, pageAllowed } = useAppConfig();
  return useMemo(() => (model ? buildNavigation(app, model, pageAllowed) : null), [app, model, pageAllowed]);
}

/**
 * The navigation an app declared, resolved for the signed-in user: entity names and page paths
 * become links, and entities the user cannot read, pages they cannot use and empty sections drop.
 * Without a declaration: labelled pages first, then entities grouped by context.
 */
export function buildNavigation(
  app: AppDefinition,
  model: AppModel,
  pageAllowed: (page: PageDefinition) => boolean,
): ShellNavSection[] {
  const pages = app.pages ?? [];
  const navigation = app.navigation;
  const iconOf = (e: ModelEntity) => entityIcon(app, e);
  const visibleModel = { ...model, entities: model.entities.filter((entity) => {
    const page = pages.find((p) => p.list === entity.logicalName);
    return !page || pageAllowed(page);
  }) };
  if (typeof navigation === "function") return navigation(model, defaultNavigation(visibleModel, [], iconOf));
  if (!navigation) {
    const links = pages
      .filter((p) => p.path && p.label && pageAllowed(p))
      .map((p) => ({ label: p.label!, icon: p.icon, path: p.path! }));
    return defaultNavigation(visibleModel, links, iconOf);
  }
  const resolve = (spec: NavItemSpec): NavItem | null => {
    const item = typeof spec === "string" ? (spec.startsWith("/") ? { path: spec } : { entity: spec }) : spec;
    if (!hasRole(model, item.roles)) return null;
    if ("items" in item) {
      const items = item.items.map(resolve).filter((i): i is NavItem => !!i);
      return items.length || (item.actions && !item.items.length)
        ? { title: item.title, icon: item.icon, ...(item.actions ? { actions: item.actions } : {}), items } : null;
    }
    if (item.entity) {
      const e = findEntity(model, item.entity);
      const page = pages.find((p) => p.list === item.entity);
      return e && (!page || pageAllowed(page)) ? { label: item.label ?? page?.label ?? e.title, icon: item.icon ?? iconOf(e), path: e.route } : null;
    }
    if (!item.path) return null;
    const page = pages.find((p) => pagePath(p, model) === item.path);
    if (!page && isEntitySurface(item.path)) {
      const base = "/" + item.path.split("?")[0].split("/").filter(Boolean).slice(0, 2).join("/");
      if (!model.entities.some((entity) => entity.route === base)) return null;
    }
    if (!page && !isEntitySurface(item.path) && import.meta.env.DEV) {
      console.warn(`[onno] navigation links "${item.path}", which is neither a page nor an entity route`);
    }
    if (page && !pageAllowed(page)) return null;
    return { label: item.label ?? page?.label ?? item.path, icon: item.icon ?? page?.icon, path: item.path };
  };
  return navigation
    .filter((s) => hasRole(model, s.roles))
    .map((s) => ({
      title: s.title ?? null,
      icon: s.icon,
      ...(s.actions ? { actions: s.actions } : {}),
      items: s.items.map(resolve).filter((i): i is NavItem => !!i),
    }))
    .filter((s) => s.items.length > 0 || !!s.actions);
}

/**
 * Open a record's screen: `openRecord("Orders", id)`, or a blank one with `openRecord("Orders")`,
 * optionally pre-filled (`openRecord("Orders", "new", { customer: id })`).
 */
export function openRecord(entity: string, id: string = "new", prefill?: Record<string, string>): void {
  const e = findEntity(modelOutsideReact(), entity);
  if (!e) return;
  const query = prefill && Object.keys(prefill).length ? "?" + new URLSearchParams(prefill).toString() : "";
  openRoute(`${e.route}/${id}${query}`);
}
