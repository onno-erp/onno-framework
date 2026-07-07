import * as React from "react";
import { Slot } from "@radix-ui/react-slot";
import { cva, type VariantProps } from "class-variance-authority";
import { Button, type ButtonProps } from "@/components/ui/button";
import { cn } from "@/lib/utils";

const attachmentVariants = cva(
  "group/attachment relative flex overflow-hidden rounded-card border bg-background text-sm transition-colors",
  {
    variants: {
      state: {
        idle: "border-border",
        uploading: "border-border",
        processing: "border-border",
        error: "border-destructive/50 bg-destructive/5",
        done: "border-border",
      },
      size: {
        default: "gap-3 p-3",
        sm: "gap-2.5 p-2.5 text-xs",
        xs: "gap-2 p-2 text-xs",
      },
      orientation: {
        horizontal: "items-center",
        vertical: "flex-col items-stretch",
      },
    },
    defaultVariants: {
      state: "done",
      size: "default",
      orientation: "horizontal",
    },
  }
);

export interface AttachmentProps
  extends React.HTMLAttributes<HTMLDivElement>,
    VariantProps<typeof attachmentVariants> {}

const Attachment = React.forwardRef<HTMLDivElement, AttachmentProps>(
  ({ className, state = "done", size = "default", orientation = "horizontal", ...props }, ref) => (
    <div
      ref={ref}
      data-state={state}
      data-size={size}
      data-orientation={orientation}
      className={cn(attachmentVariants({ state, size, orientation, className }))}
      {...props}
    />
  )
);
Attachment.displayName = "Attachment";

export interface AttachmentMediaProps extends React.HTMLAttributes<HTMLDivElement> {
  variant?: "icon" | "image";
}

const AttachmentMedia = React.forwardRef<HTMLDivElement, AttachmentMediaProps>(
  ({ className, variant = "icon", ...props }, ref) => (
    <div
      ref={ref}
      data-variant={variant}
      className={cn(
        "relative z-20 shrink-0 overflow-hidden rounded-field pointer-events-none",
        variant === "icon"
          ? "grid size-10 place-items-center bg-muted text-muted-foreground group-data-[size=xs]/attachment:size-7 group-data-[size=sm]/attachment:size-8 [&_svg]:size-5 group-data-[size=xs]/attachment:[&_svg]:size-3.5 group-data-[size=sm]/attachment:[&_svg]:size-4"
          : "aspect-video w-full bg-muted [&_img]:h-full [&_img]:w-full [&_img]:object-cover",
        className
      )}
      {...props}
    />
  )
);
AttachmentMedia.displayName = "AttachmentMedia";

const AttachmentContent = React.forwardRef<
  HTMLDivElement,
  React.HTMLAttributes<HTMLDivElement>
>(({ className, ...props }, ref) => (
  <div
    ref={ref}
    className={cn("relative z-20 min-w-0 flex-1 pointer-events-none", className)}
    {...props}
  />
));
AttachmentContent.displayName = "AttachmentContent";

const AttachmentTitle = React.forwardRef<
  HTMLDivElement,
  React.HTMLAttributes<HTMLDivElement>
>(({ className, ...props }, ref) => (
  <div
    ref={ref}
    className={cn(
      "truncate font-medium text-foreground group-data-[state=processing]/attachment:animate-pulse group-data-[state=uploading]/attachment:animate-pulse",
      className
    )}
    {...props}
  />
));
AttachmentTitle.displayName = "AttachmentTitle";

const AttachmentDescription = React.forwardRef<
  HTMLDivElement,
  React.HTMLAttributes<HTMLDivElement>
>(({ className, ...props }, ref) => (
  <div
    ref={ref}
    className={cn(
      "truncate text-xs text-muted-foreground group-data-[state=error]/attachment:text-destructive",
      className
    )}
    {...props}
  />
));
AttachmentDescription.displayName = "AttachmentDescription";

const AttachmentActions = React.forwardRef<
  HTMLDivElement,
  React.HTMLAttributes<HTMLDivElement>
>(({ className, ...props }, ref) => (
  <div
    ref={ref}
    className={cn("relative z-30 ml-auto flex shrink-0 items-center gap-1", className)}
    {...props}
  />
));
AttachmentActions.displayName = "AttachmentActions";

export interface AttachmentActionProps extends ButtonProps {}

const AttachmentAction = React.forwardRef<HTMLButtonElement, AttachmentActionProps>(
  ({ className, variant = "ghost", size = "icon", ...props }, ref) => (
    <Button
      ref={ref}
      variant={variant}
      size={size}
      className={cn("h-7 w-7 rounded-control", className)}
      {...props}
    />
  )
);
AttachmentAction.displayName = "AttachmentAction";

export interface AttachmentTriggerProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  asChild?: boolean;
}

const AttachmentTrigger = React.forwardRef<HTMLButtonElement, AttachmentTriggerProps>(
  ({ className, asChild = false, ...props }, ref) => {
    const Comp = asChild ? Slot : "button";
    return (
      <Comp
        ref={ref}
        className={cn(
          "absolute inset-0 z-10 rounded-card focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2",
          className
        )}
        {...props}
      />
    );
  }
);
AttachmentTrigger.displayName = "AttachmentTrigger";

const AttachmentGroup = React.forwardRef<
  HTMLDivElement,
  React.HTMLAttributes<HTMLDivElement>
>(({ className, ...props }, ref) => (
  <div
    ref={ref}
    className={cn(
      "flex gap-2 overflow-x-auto overscroll-x-contain scroll-smooth pb-1 [scroll-snap-type:x_mandatory] [&>[data-slot=attachment]]:min-w-64 [&>[data-slot=attachment]]:snap-start",
      className
    )}
    {...props}
  />
));
AttachmentGroup.displayName = "AttachmentGroup";

export {
  Attachment,
  AttachmentAction,
  AttachmentActions,
  AttachmentContent,
  AttachmentDescription,
  AttachmentGroup,
  AttachmentMedia,
  AttachmentTitle,
  AttachmentTrigger,
};
