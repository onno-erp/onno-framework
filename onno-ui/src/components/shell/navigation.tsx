import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useId,
  useMemo,
  useState,
  type MouseEvent,
  type ReactNode,
} from "react";
import { ChevronRight } from "lucide-react";
import { Icon } from "../../lib/icons";
import { placementForClick, type OpenPlacement } from "../../lib/open-placement";
import type { IconSource } from "../../lib/icons";
import type {
  ShellNavItem,
  ShellNavNode,
  ShellNavSection,
} from "../../lib/view-types";
import { navigationActive } from "../../lib/navigation-tree";
import { buildNavigation, useAppConfig } from "../../lib/app-config";
import { useModel } from "../../lib/model-store";
import { openRoute } from "../../lib/navigation";
import { cn } from "../../lib/utils";
import { NavPresenceIndicator } from "../collaboration/nav-presence";

export type NavigationProps = {
  navigation: ShellNavSection[];
  activePath: string;
  mobile: boolean;
  header?: ReactNode;
  footer?: ReactNode;
};
/**
 * Opens a navigation item. `newWorkspaceTab` asks for a new workspace tab (middle- or Ctrl/⌘-click),
 * `newIsland` for a new island beside the focused one (Shift-click).
 */
export type NavigateHandler = (path: string, options?: { newWorkspaceTab?: boolean; newIsland?: boolean }) => void;

function navigateWith(onNavigate: NavigateHandler, path: string, placement: OpenPlacement) {
  if (placement === "new-island") onNavigate(path, { newIsland: true });
  else if (placement === "new-tab") onNavigate(path, { newWorkspaceTab: true });
  else onNavigate(path);
}

/**
 * Click handlers for a navigation button, with a browser's modifiers: a plain click navigates the
 * active tab, a middle-click or a Ctrl/⌘-click opens a new workspace tab, a Shift-click opens a new
 * island beside the focused one.
 */
export function navigationClickHandlers(path: string, onNavigate: NavigateHandler) {
  return {
    onClick: (event: MouseEvent) => navigateWith(onNavigate, path, placementForClick(event)),
    onAuxClick: (event: MouseEvent) => {
      if (event.button !== 1) return;
      event.preventDefault();
      navigateWith(onNavigate, path, "new-tab");
    },
    // A middle press would otherwise start the browser's autoscroll, a Shift press a text selection.
    onMouseDown: (event: MouseEvent) => {
      if (event.button === 1 || event.shiftKey) event.preventDefault();
    },
  };
}

type NavigationContext = {
  activePath: string;
  mobile: boolean;
  onNavigate: NavigateHandler;
};
const NavigationContext = createContext<NavigationContext>({
  activePath: "",
  mobile: false,
  onNavigate: openRoute,
});
const RevealGroup = createContext<(() => void) | null>(null);

/** Supplies the shell's navigation dispatcher, including its unsaved-edits guard. */
export function NavigationProvider({
  activePath,
  mobile = false,
  onNavigate,
  children,
}: NavigationContext & { children: ReactNode }) {
  const value = useMemo(
    () => ({ activePath, mobile, onNavigate }),
    [activePath, mobile, onNavigate],
  );
  return (
    <NavigationContext.Provider value={value}>
      {children}
    </NavigationContext.Provider>
  );
}

/** The optional app component replaces the desktop navigation or the mobile menu's body. */
export function NavigationHost({
  navigation,
  activePath,
  mobile,
  onNavigate,
  children,
  header,
  footer,
}: NavigationProps & {
  onNavigate: NavigateHandler;
  children: ReactNode;
}) {
  const Custom = useAppConfig().app.navigationComponent;
  return (
    <NavigationProvider
      activePath={activePath}
      mobile={mobile}
      onNavigate={onNavigate}
    >
      {Custom ? (
        <Custom
          navigation={navigation}
          activePath={activePath}
          mobile={mobile}
          header={header}
          footer={footer}
        />
      ) : (
        children
      )}
    </NavigationProvider>
  );
}

/** A scrollable navigation surface; arbitrary React children can be placed between its blocks. */
export function Navigation({
  children,
  className,
  label = "Navigation",
}: {
  children: ReactNode;
  className?: string;
  label?: string;
}) {
  const { mobile } = useContext(NavigationContext);
  return (
    <nav
      aria-label={label}
      className={cn(
        "flex min-h-0 flex-col gap-3 overflow-y-auto bg-card p-3",
        mobile ? "w-full" : "h-screen w-64 shrink-0 border-r border-border",
        className,
      )}
    >
      {children}
    </nav>
  );
}

export function NavigationSection({
  title,
  actions,
  children,
  className,
}: {
  title?: string | null;
  actions?: ReactNode;
  children: ReactNode;
  className?: string;
}) {
  const id = useId();
  return (
    <section
      aria-labelledby={title ? id : undefined}
      className={cn("space-y-1", className)}
    >
      {title || actions ? (
        <div className="flex min-h-9 items-center justify-between gap-2 px-2">
          {title ? (
            <h2 id={id} className="truncate text-sm font-medium">
              {title}
            </h2>
          ) : (
            <span />
          )}
          {actions}
        </div>
      ) : null}
      {children}
    </section>
  );
}

/** Expandable groups nest without a depth limit. Active descendants reveal their ancestors. */
export function NavigationGroup({
  title,
  icon,
  actions,
  children,
  defaultOpen = false,
  active = false,
}: {
  title: string;
  icon?: IconSource | null;
  actions?: ReactNode;
  children: ReactNode;
  defaultOpen?: boolean;
  active?: boolean;
}) {
  const { activePath } = useContext(NavigationContext);
  const parentReveal = useContext(RevealGroup);
  const [expanded, setExpanded] = useState(defaultOpen || active);
  const id = useId();
  const reveal = useCallback(() => {
    setExpanded(true);
    parentReveal?.();
  }, [parentReveal]);
  useEffect(() => {
    if (active) reveal();
  }, [active, activePath, reveal]);
  return (
    <div>
      <div className="flex items-center gap-1">
        <button
          type="button"
          aria-expanded={expanded}
          aria-controls={id}
          onClick={() => setExpanded((value) => !value)}
          className="flex min-w-0 flex-1 items-center gap-2 rounded-field px-2 py-2 text-left text-sm hover:bg-muted"
        >
          <ChevronRight
            className={cn(
              "size-3.5 shrink-0 transition-transform",
              expanded && "rotate-90",
            )}
          />
          {icon ? <Icon name={icon} size={15} /> : null}
          <span className="truncate">{title}</span>
        </button>
        {actions}
      </div>
      <div
        id={id}
        hidden={!expanded}
        className="ml-3 border-l border-border pl-2"
      >
        <RevealGroup.Provider value={reveal}>{children}</RevealGroup.Provider>
      </div>
    </div>
  );
}

function Destination({
  item,
  className,
}: {
  item: ShellNavItem;
  className?: string;
}) {
  const { activePath, mobile, onNavigate } = useContext(NavigationContext);
  const reveal = useContext(RevealGroup);
  const active = navigationActive([item], activePath);
  useEffect(() => {
    if (active) reveal?.();
  }, [active, activePath, reveal]);
  return (
    <button
      type="button"
      aria-label={item.label}
      aria-current={active ? "page" : undefined}
      {...navigationClickHandlers(item.path, onNavigate)}
      className={cn(
        "flex w-full items-center gap-2 rounded-field px-2 text-left text-sm transition-colors hover:bg-muted",
        mobile ? "py-3" : "py-1.5",
        active && "bg-accent text-primary",
        className,
      )}
    >
      <span className={cn("shrink-0", !active && "text-muted-foreground")}>
        <Icon name={item.icon || "circle"} size={15} />
      </span>
      <span className="min-w-0 flex-1 truncate">{item.label}</span>
      {!mobile ? (
        <span className="h-5 w-12 shrink-0">
          <NavPresenceIndicator path={item.path} />
        </span>
      ) : null}
    </button>
  );
}

/** A hand-authored link uses the same model and page gates as declared navigation. */
export function NavigationLink({
  entity,
  path,
  label,
  icon,
  roles,
  className,
}: {
  entity?: string;
  path?: string;
  label?: string;
  icon?: IconSource;
  roles?: string[];
  className?: string;
}) {
  const { app, pageAllowed } = useAppConfig();
  const model = useModel();
  if (!model) return null;
  const sections = buildNavigation(
    { ...app, navigation: [{ items: [{ entity, path, label, icon, roles }] }] },
    model,
    pageAllowed,
  );
  const item = sections[0]?.items[0];
  return item && !("items" in item) ? (
    <Destination item={item} className={className} />
  ) : null;
}

/** Draw a resolved tree, or only a section's items when composing the standard drawer. */
export function NavigationItems({ items }: { items: ShellNavNode[] }) {
  const { activePath } = useContext(NavigationContext);
  return (
    <>
      {items.map((item, index) =>
        "items" in item ? (
          <NavigationGroup
            key={`group:${item.title}:${index}`}
            title={item.title}
            icon={item.icon}
            actions={item.actions}
            active={navigationActive(item.items, activePath)}
          >
            <NavigationItems items={item.items} />
          </NavigationGroup>
        ) : (
          <Destination key={item.path} item={item} />
        ),
      )}
    </>
  );
}
export function DefaultNavigation({
  sections,
}: {
  sections: ShellNavSection[];
}) {
  return (
    <>
      {sections.map((section, index) => (
        <NavigationSection
          key={`${section.title}:${index}`}
          title={section.title}
          actions={section.actions}
        >
          <NavigationItems items={section.items} />
        </NavigationSection>
      ))}
    </>
  );
}
