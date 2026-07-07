import * as React from "react";
import * as SelectPrimitive from "@radix-ui/react-select";
import { Check, ChevronDown, ChevronUp } from "lucide-react";
import { Drawer } from "vaul";
import { cn } from "@/lib/utils";
import { useTouchLayout } from "@/components/ui/facet-sheet";

const SelectOpenContext = React.createContext<{
  drawerOpen: boolean;
  setOpen: (open: boolean) => void;
}>({ drawerOpen: false, setOpen: () => {} });

const Select = ({ open: openProp, defaultOpen, onOpenChange, ...props }: React.ComponentPropsWithoutRef<typeof SelectPrimitive.Root>) => {
  const touchLayout = useTouchLayout();
  const [uncontrolledOpen, setUncontrolledOpen] = React.useState(defaultOpen ?? false);
  const requestedOpen = openProp ?? uncontrolledOpen;
  const [mountedOpen, setMountedOpen] = React.useState(requestedOpen);
  const [drawerOpen, setDrawerOpen] = React.useState(requestedOpen);
  const closeTimer = React.useRef<number | null>(null);

  React.useEffect(() => {
    if (!touchLayout) {
      setMountedOpen(requestedOpen);
      setDrawerOpen(requestedOpen);
      return;
    }
    if (requestedOpen) {
      if (closeTimer.current) window.clearTimeout(closeTimer.current);
      closeTimer.current = null;
      setMountedOpen(true);
      requestAnimationFrame(() => setDrawerOpen(true));
      return;
    }
    setDrawerOpen(false);
    closeTimer.current = window.setTimeout(() => {
      setMountedOpen(false);
      closeTimer.current = null;
    }, 260);
    return () => {
      if (closeTimer.current) window.clearTimeout(closeTimer.current);
      closeTimer.current = null;
    };
  }, [requestedOpen, touchLayout]);

  const setOpen = React.useCallback(
    (next: boolean) => {
      if (openProp === undefined) setUncontrolledOpen(next);
      onOpenChange?.(next);
    },
    [onOpenChange, openProp]
  );

  return (
    <SelectOpenContext.Provider value={{ drawerOpen, setOpen }}>
      <SelectPrimitive.Root open={touchLayout ? mountedOpen : requestedOpen} onOpenChange={setOpen} {...props} />
    </SelectOpenContext.Provider>
  );
};
const SelectGroup = SelectPrimitive.Group;
const SelectValue = SelectPrimitive.Value;

const SelectTrigger = React.forwardRef<
  React.ComponentRef<typeof SelectPrimitive.Trigger>,
  React.ComponentPropsWithoutRef<typeof SelectPrimitive.Trigger>
>(({ className, children, ...props }, ref) => (
  <SelectPrimitive.Trigger
    ref={ref}
    className={cn(
      "flex h-9 w-full items-center justify-between whitespace-nowrap rounded-field border border-input bg-muted px-3 py-2 text-sm shadow-sm ring-offset-background placeholder:text-muted-foreground focus:outline-none focus:ring-1 focus:ring-ring disabled:cursor-not-allowed disabled:opacity-50 [&>span]:line-clamp-1",
      className
    )}
    {...props}
  >
    {children}
    <SelectPrimitive.Icon asChild>
      <ChevronDown className="h-4 w-4 opacity-50" />
    </SelectPrimitive.Icon>
  </SelectPrimitive.Trigger>
));
SelectTrigger.displayName = SelectPrimitive.Trigger.displayName;

const SelectScrollUpButton = React.forwardRef<
  React.ComponentRef<typeof SelectPrimitive.ScrollUpButton>,
  React.ComponentPropsWithoutRef<typeof SelectPrimitive.ScrollUpButton>
>(({ className, ...props }, ref) => (
  <SelectPrimitive.ScrollUpButton
    ref={ref}
    className={cn("flex cursor-default items-center justify-center py-1", className)}
    {...props}
  >
    <ChevronUp className="h-4 w-4" />
  </SelectPrimitive.ScrollUpButton>
));
SelectScrollUpButton.displayName = SelectPrimitive.ScrollUpButton.displayName;

const SelectScrollDownButton = React.forwardRef<
  React.ComponentRef<typeof SelectPrimitive.ScrollDownButton>,
  React.ComponentPropsWithoutRef<typeof SelectPrimitive.ScrollDownButton>
>(({ className, ...props }, ref) => (
  <SelectPrimitive.ScrollDownButton
    ref={ref}
    className={cn("flex cursor-default items-center justify-center py-1", className)}
    {...props}
  >
    <ChevronDown className="h-4 w-4" />
  </SelectPrimitive.ScrollDownButton>
));
SelectScrollDownButton.displayName = SelectPrimitive.ScrollDownButton.displayName;

const SelectContent = React.forwardRef<
  React.ComponentRef<typeof SelectPrimitive.Content>,
  React.ComponentPropsWithoutRef<typeof SelectPrimitive.Content>
>(({ className, children, position = "popper", ...props }, ref) => {
  const touchLayout = useTouchLayout();
  const { drawerOpen, setOpen } = React.useContext(SelectOpenContext);
  if (touchLayout) {
    return (
      <Drawer.Root open={drawerOpen} onOpenChange={setOpen} direction="bottom" closeThreshold={0.32}>
        <Drawer.Portal>
          <Drawer.Overlay className="fixed inset-0 z-50 bg-black/50" />
          <Drawer.Content asChild>
            <SelectPrimitive.Content
              ref={ref}
              className={cn(
                "fixed inset-x-0 bottom-0 z-50 max-h-[85dvh] w-full min-w-0 overflow-hidden rounded-b-none rounded-t-2xl border border-x-0 border-b-0 bg-popover pb-[max(env(safe-area-inset-bottom),0.75rem)] text-popover-foreground shadow-md outline-none",
                className
              )}
              position="item-aligned"
              {...props}
            >
              <Drawer.Handle className="mx-auto mt-2 h-1 w-10 rounded-full bg-border" />
              <Drawer.Title className="sr-only">Select option</Drawer.Title>
              <SelectScrollUpButton />
              <SelectPrimitive.Viewport className="max-h-[calc(85dvh-3rem)] p-2">
                {children}
              </SelectPrimitive.Viewport>
              <SelectScrollDownButton />
            </SelectPrimitive.Content>
          </Drawer.Content>
        </Drawer.Portal>
      </Drawer.Root>
    );
  }
  return (
    <SelectPrimitive.Portal>
      <SelectPrimitive.Content
        ref={ref}
        className={cn(
          "relative z-50 max-h-96 min-w-[8rem] overflow-hidden rounded-card border bg-popover text-popover-foreground shadow-md data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0 data-[state=closed]:zoom-out-95 data-[state=open]:zoom-in-95 data-[side=bottom]:slide-in-from-top-2 data-[side=left]:slide-in-from-right-2 data-[side=right]:slide-in-from-left-2 data-[side=top]:slide-in-from-bottom-2",
          position === "popper" &&
            "data-[side=bottom]:translate-y-1 data-[side=left]:-translate-x-1 data-[side=right]:translate-x-1 data-[side=top]:-translate-y-1",
          className
        )}
        position={position}
        {...props}
      >
        <SelectScrollUpButton />
        <SelectPrimitive.Viewport
          className={cn(
            "p-1",
            position === "popper" &&
              "h-[var(--radix-select-trigger-height)] w-full min-w-[var(--radix-select-trigger-width)]"
          )}
        >
          {children}
        </SelectPrimitive.Viewport>
        <SelectScrollDownButton />
      </SelectPrimitive.Content>
    </SelectPrimitive.Portal>
  );
});
SelectContent.displayName = SelectPrimitive.Content.displayName;

const SelectItem = React.forwardRef<
  React.ComponentRef<typeof SelectPrimitive.Item>,
  React.ComponentPropsWithoutRef<typeof SelectPrimitive.Item>
>(({ className, children, ...props }, ref) => (
  <SelectPrimitive.Item
    ref={ref}
    className={cn(
      "relative flex w-full cursor-default select-none items-center rounded-field py-1.5 pl-8 pr-2 text-sm outline-none focus:bg-accent focus:text-accent-foreground data-[state=checked]:font-medium data-[disabled]:pointer-events-none data-[disabled]:opacity-50",
      className
    )}
    {...props}
  >
    <span className="absolute left-2 flex h-3.5 w-3.5 items-center justify-center">
      <SelectPrimitive.ItemIndicator>
        <Check className="h-4 w-4" />
      </SelectPrimitive.ItemIndicator>
    </span>
    <SelectPrimitive.ItemText>{children}</SelectPrimitive.ItemText>
  </SelectPrimitive.Item>
));
SelectItem.displayName = SelectPrimitive.Item.displayName;

export {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectScrollDownButton,
  SelectScrollUpButton,
  SelectTrigger,
  SelectValue,
};
