import * as React from "react";
import { Drawer as DrawerPrimitive } from "@base-ui/react/drawer";
import { cn } from "@/lib/utils";

const Drawer = DrawerPrimitive.Root;
const DrawerTrigger = DrawerPrimitive.Trigger;
const DrawerClose = DrawerPrimitive.Close;
const DrawerTitle = DrawerPrimitive.Title;
const DrawerDescription = DrawerPrimitive.Description;

const DrawerContent = React.forwardRef<
  HTMLDivElement,
  React.ComponentPropsWithoutRef<typeof DrawerPrimitive.Popup> & {
    overlayClassName?: string;
    viewportClassName?: string;
  }
>(({ className, overlayClassName, viewportClassName, children, ...props }, ref) => (
  <DrawerPrimitive.Portal>
    <DrawerPrimitive.Backdrop
      data-slot="drawer-overlay"
      className={cn("onno-drawer-overlay fixed inset-0 z-40 bg-black/50 backdrop-blur-[2px]", overlayClassName)}
    />
    <DrawerPrimitive.Viewport
      data-slot="drawer-viewport"
      className={cn("pointer-events-none fixed inset-0 z-50", viewportClassName)}
    >
      <DrawerPrimitive.Popup
        ref={ref}
        data-slot="drawer-content"
        className={cn(
          "onno-drawer-popup pointer-events-auto fixed overflow-hidden border border-border bg-background text-foreground shadow-2xl outline-none",
          className
        )}
        {...props}
      >
        <DrawerPrimitive.Content className="flex h-full min-h-0 flex-col">
          {children}
        </DrawerPrimitive.Content>
      </DrawerPrimitive.Popup>
    </DrawerPrimitive.Viewport>
  </DrawerPrimitive.Portal>
));
DrawerContent.displayName = "DrawerContent";

function DrawerHeader({ className, ...props }: React.HTMLAttributes<HTMLDivElement>) {
  return <div data-slot="drawer-header" className={cn("grid gap-1.5 p-4", className)} {...props} />;
}

function DrawerFooter({ className, ...props }: React.HTMLAttributes<HTMLDivElement>) {
  return <div data-slot="drawer-footer" className={cn("mt-auto flex flex-col gap-2 p-4", className)} {...props} />;
}

export {
  Drawer,
  DrawerClose,
  DrawerContent,
  DrawerDescription,
  DrawerFooter,
  DrawerHeader,
  DrawerTitle,
  DrawerTrigger,
};
