import * as React from "react";
import { createPortal } from "react-dom";
import { Check, ChevronRight } from "lucide-react";
import { cn } from "@/lib/utils";

type Point = { x: number; y: number };

type ContextMenuState = {
  open: boolean;
  position: Point | null;
  setOpen: (open: boolean) => void;
  openAt: (position: Point) => void;
};

const ContextMenuContext = React.createContext<ContextMenuState | null>(null);

function useOptionalContextMenu() {
  return React.useContext(ContextMenuContext);
}

function clamp(position: Point, width: number, height: number): Point {
  if (typeof window === "undefined") return position;
  return {
    x: Math.max(8, Math.min(position.x, window.innerWidth - width - 8)),
    y: Math.max(8, Math.min(position.y, window.innerHeight - height - 8)),
  };
}

function ContextMenu({
  children,
  open: controlledOpen,
  onOpenChange,
}: {
  children: React.ReactNode;
  open?: boolean;
  onOpenChange?: (open: boolean) => void;
}) {
  const [uncontrolledOpen, setUncontrolledOpen] = React.useState(false);
  const [position, setPosition] = React.useState<Point | null>(null);
  const open = controlledOpen ?? uncontrolledOpen;
  const setOpen = React.useCallback(
    (next: boolean) => {
      if (controlledOpen === undefined) setUncontrolledOpen(next);
      onOpenChange?.(next);
    },
    [controlledOpen, onOpenChange]
  );
  const openAt = React.useCallback(
    (nextPosition: Point) => {
      setPosition(nextPosition);
      setOpen(true);
    },
    [setOpen]
  );

  return (
    <ContextMenuContext.Provider value={{ open, position, setOpen, openAt }}>
      {children}
    </ContextMenuContext.Provider>
  );
}

const ContextMenuTrigger = React.forwardRef<
  HTMLDivElement,
  React.HTMLAttributes<HTMLDivElement> & { asChild?: boolean }
>(({ asChild = false, onContextMenu, children, ...props }, ref) => {
  const ctx = useOptionalContextMenu();
  const child = React.Children.only(children) as React.ReactElement | undefined;
  const handleContextMenu = (e: React.MouseEvent<HTMLDivElement>) => {
    onContextMenu?.(e);
    if (e.defaultPrevented) return;
    e.preventDefault();
    ctx?.openAt({ x: e.clientX, y: e.clientY });
  };

  if (asChild && React.isValidElement(child)) {
    return React.cloneElement(child, {
      ...props,
      ref,
      onContextMenu: handleContextMenu,
    } as React.HTMLAttributes<HTMLElement>);
  }

  return (
    <div ref={ref} onContextMenu={handleContextMenu} {...props}>
      {children}
    </div>
  );
});
ContextMenuTrigger.displayName = "ContextMenuTrigger";

type ContextMenuContentProps = React.HTMLAttributes<HTMLDivElement> & {
  open?: boolean;
  position?: Point | null;
  onOpenChange?: (open: boolean) => void;
  width?: number;
  estimatedHeight?: number;
};

const ContextMenuContent = React.forwardRef<HTMLDivElement, ContextMenuContentProps>(
  (
    {
      className,
      children,
      open: controlledOpen,
      position: controlledPosition,
      onOpenChange,
      width = 192,
      estimatedHeight = 240,
      style,
      ...props
    },
    ref
  ) => {
    const ctx = useOptionalContextMenu();
    const open = controlledOpen ?? ctx?.open ?? false;
    const position = controlledPosition ?? ctx?.position ?? null;
    const close = React.useCallback(() => {
      onOpenChange?.(false);
      ctx?.setOpen(false);
    }, [ctx, onOpenChange]);

    React.useEffect(() => {
      if (!open) return;
      const onKey = (e: KeyboardEvent) => {
        if (e.key !== "Escape" || e.defaultPrevented) return;
        // Consume the key (capture phase): an open menu is the topmost Esc layer — without this,
        // one press would also clear a row selection and/or close the workspace tab beneath it.
        e.preventDefault();
        close();
      };
      window.addEventListener("click", close);
      window.addEventListener("resize", close);
      window.addEventListener("keydown", onKey, true);
      return () => {
        window.removeEventListener("click", close);
        window.removeEventListener("resize", close);
        window.removeEventListener("keydown", onKey, true);
      };
    }, [close, open]);

    if (!open || !position || typeof document === "undefined") return null;
    const clamped = clamp(position, width, estimatedHeight);

    return createPortal(
      <div
        ref={ref}
        role="menu"
        className={cn(
          "fixed z-50 overflow-hidden rounded-card border bg-popover p-1 text-popover-foreground shadow-lg outline-none animate-in fade-in-0 zoom-in-95",
          className
        )}
        style={{ left: clamped.x, top: clamped.y, minWidth: width, ...style }}
        onClick={(e) => e.stopPropagation()}
        onContextMenu={(e) => e.preventDefault()}
        {...props}
      >
        {children}
      </div>,
      document.body
    );
  }
);
ContextMenuContent.displayName = "ContextMenuContent";

const ContextMenuGroup = React.forwardRef<HTMLDivElement, React.HTMLAttributes<HTMLDivElement>>(
  ({ className, ...props }, ref) => (
    <div ref={ref} role="group" className={cn("py-1", className)} {...props} />
  )
);
ContextMenuGroup.displayName = "ContextMenuGroup";

const ContextMenuLabel = React.forwardRef<
  HTMLDivElement,
  React.HTMLAttributes<HTMLDivElement> & { inset?: boolean }
>(({ className, inset, ...props }, ref) => (
  <div
    ref={ref}
    className={cn("px-2 py-1.5 text-xs font-medium text-muted-foreground", inset && "pl-8", className)}
    {...props}
  />
));
ContextMenuLabel.displayName = "ContextMenuLabel";

const ContextMenuSeparator = React.forwardRef<HTMLDivElement, React.HTMLAttributes<HTMLDivElement>>(
  ({ className, ...props }, ref) => (
    <div ref={ref} className={cn("-mx-1 my-1 h-px bg-border", className)} {...props} />
  )
);
ContextMenuSeparator.displayName = "ContextMenuSeparator";

type ContextMenuItemProps = React.ButtonHTMLAttributes<HTMLButtonElement> & {
  inset?: boolean;
  variant?: "default" | "destructive";
  onSelect?: () => void;
};

const ContextMenuItem = React.forwardRef<HTMLButtonElement, ContextMenuItemProps>(
  ({ className, inset, variant = "default", onClick, onSelect, disabled, ...props }, ref) => (
    <button
      ref={ref}
      type="button"
      role="menuitem"
      disabled={disabled}
      className={cn(
        // rounded-field (not rounded-control): the control token is a full pill by default, which
        // reads as a lozenge on a hovered menu row — menus share the moderate field radius the
        // filter/select dropdowns use.
        "relative flex w-full select-none items-center gap-2 rounded-field px-2 py-1.5 text-left text-sm outline-none transition-colors hover:bg-accent hover:text-accent-foreground focus:bg-accent focus:text-accent-foreground disabled:pointer-events-none disabled:opacity-50 [&_svg]:size-4 [&_svg]:shrink-0",
        inset && "pl-8",
        variant === "destructive" && "text-destructive hover:text-destructive focus:text-destructive",
        className
      )}
      onClick={(e) => {
        onClick?.(e);
        if (!e.defaultPrevented) onSelect?.();
      }}
      {...props}
    />
  )
);
ContextMenuItem.displayName = "ContextMenuItem";

const ContextMenuCheckboxItem = React.forwardRef<
  HTMLButtonElement,
  ContextMenuItemProps & { checked?: boolean }
>(({ checked, children, className, ...props }, ref) => (
  <ContextMenuItem ref={ref} className={cn("pl-8", className)} {...props}>
    <span className="absolute left-2 flex size-4 items-center justify-center">
      {checked ? <Check className="size-4" aria-hidden="true" /> : null}
    </span>
    {children}
  </ContextMenuItem>
));
ContextMenuCheckboxItem.displayName = "ContextMenuCheckboxItem";

const ContextMenuSubTrigger = React.forwardRef<HTMLButtonElement, ContextMenuItemProps>(
  ({ children, className, ...props }, ref) => (
    <ContextMenuItem ref={ref} className={cn("pr-8", className)} {...props}>
      {children}
      <ChevronRight className="ml-auto size-4" aria-hidden="true" />
    </ContextMenuItem>
  )
);
ContextMenuSubTrigger.displayName = "ContextMenuSubTrigger";

/**
 * A hover/click-opened flyout submenu. Self-contained (no Radix): the trigger row opens the panel
 * to its right (flipped left when it would overflow), a short close delay bridges the diagonal
 * mouse path from trigger to panel, and the panel closes with the parent menu since it lives
 * inside the parent's DOM subtree (the parent's outside-click/Escape handling covers it).
 */
function ContextMenuSub({
  label,
  icon,
  className,
  style,
  width = 200,
  children,
}: {
  label: React.ReactNode;
  icon?: React.ReactNode;
  className?: string;
  /** Panel style override (e.g. themed background/border to match the parent menu). */
  style?: React.CSSProperties;
  width?: number;
  children: React.ReactNode;
}) {
  const [open, setOpen] = React.useState(false);
  const [pos, setPos] = React.useState<{ left: number; top: number } | null>(null);
  const rowRef = React.useRef<HTMLDivElement>(null);
  const panelRef = React.useRef<HTMLDivElement>(null);
  const closeTimer = React.useRef<number | null>(null);

  const cancelClose = () => {
    if (closeTimer.current != null) {
      window.clearTimeout(closeTimer.current);
      closeTimer.current = null;
    }
  };
  const openNow = () => {
    cancelClose();
    const r = rowRef.current?.getBoundingClientRect();
    if (!r) return;
    // Right of the row, slightly overlapped; flip to the left edge when it would overflow.
    const left = r.right + width + 4 > window.innerWidth ? Math.max(8, r.left - width + 4) : r.right - 4;
    const top = Math.max(8, Math.min(r.top - 4, window.innerHeight - 8 - (panelRef.current?.offsetHeight ?? 160)));
    setPos({ left, top });
    setOpen(true);
  };
  const scheduleClose = () => {
    cancelClose();
    closeTimer.current = window.setTimeout(() => setOpen(false), 180);
  };
  React.useEffect(() => cancelClose, []);

  return (
    <div ref={rowRef} onMouseEnter={openNow} onMouseLeave={scheduleClose}>
      <ContextMenuSubTrigger
        aria-expanded={open}
        aria-haspopup="menu"
        className={cn(open && "bg-accent text-accent-foreground")}
        onClick={(e) => {
          e.preventDefault(); // don't let onSelect/close fire — the trigger only opens the flyout
          openNow();
        }}
      >
        {icon}
        {label}
      </ContextMenuSubTrigger>
      {open && pos ? (
        <div
          ref={panelRef}
          role="menu"
          className={cn(
            "fixed z-50 overflow-hidden rounded-card border bg-popover p-1 text-popover-foreground shadow-lg outline-none animate-in fade-in-0 zoom-in-95",
            className
          )}
          style={{ left: pos.left, top: pos.top, minWidth: width, ...style }}
          onMouseEnter={cancelClose}
          onMouseLeave={scheduleClose}
        >
          {children}
        </div>
      ) : null}
    </div>
  );
}

const ContextMenuShortcut = ({ className, ...props }: React.HTMLAttributes<HTMLSpanElement>) => (
  <span
    className={cn("ml-auto pl-4 text-xs tracking-normal text-muted-foreground", className)}
    {...props}
  />
);
ContextMenuShortcut.displayName = "ContextMenuShortcut";

export {
  ContextMenu,
  ContextMenuCheckboxItem,
  ContextMenuContent,
  ContextMenuGroup,
  ContextMenuItem,
  ContextMenuLabel,
  ContextMenuSeparator,
  ContextMenuShortcut,
  ContextMenuSub,
  ContextMenuSubTrigger,
  ContextMenuTrigger,
};
