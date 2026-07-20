import { useId, useState, type ReactNode } from "react";
import { CheckCircle2, CircleX, Info, TriangleAlert, X } from "lucide-react";
import {
  Dialog,
  Heading,
  Modal,
  ModalOverlay,
} from "react-aria-components";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import { DialogPortalContext } from "@/components/ui/dialog-portal";
import type { ActionSeverity } from "@/lib/types";
import { useMessages } from "@/providers/messages-provider";

export type DialogShellSize = "sm" | "md" | "lg";

const sizeClass: Record<DialogShellSize, string> = {
  sm: "sm:max-w-sm",
  md: "sm:max-w-xl",
  lg: "sm:max-w-3xl",
};

const toneClass: Record<ActionSeverity, string> = {
  info: "border-primary/30 bg-primary/10 text-primary",
  success: "border-[hsl(var(--success))]/30 bg-[hsl(var(--success))]/10 text-[hsl(var(--success))]",
  warning: "border-amber-500/30 bg-amber-500/10 text-amber-700 dark:text-amber-300",
  error: "border-destructive/30 bg-destructive/10 text-destructive",
};

const toneIcon = {
  info: Info,
  success: CheckCircle2,
  warning: TriangleAlert,
  error: CircleX,
};

/**
 * Canonical accessible modal shell for action forms, confirmations, and action feedback.
 * React Aria owns focus containment, focus restoration, Escape and backdrop dismissal.
 */
export function DialogShell({
  open = true,
  onOpenChange,
  title,
  description,
  tone = "info",
  size = "md",
  icon,
  dismissable = true,
  footer,
  children,
  role = "dialog",
}: {
  open?: boolean;
  onOpenChange: (open: boolean) => void;
  title: string;
  description?: string | null;
  tone?: ActionSeverity;
  size?: DialogShellSize;
  icon?: ReactNode;
  dismissable?: boolean;
  footer?: ReactNode;
  children?: ReactNode;
  role?: "dialog" | "alertdialog";
}) {
  const t = useMessages();
  const titleId = useId();
  const descriptionId = useId();
  const ToneIcon = toneIcon[tone];
  const [portalContainer, setPortalContainer] = useState<HTMLDivElement | null>(null);

  return (
    <ModalOverlay
      isOpen={open}
      onOpenChange={onOpenChange}
      isDismissable={dismissable}
      className={({ isEntering, isExiting }) =>
        cn(
          "fixed inset-0 z-[70] flex items-center justify-center bg-black/50 p-4 backdrop-blur-[1px]",
          "motion-reduce:transition-none",
          isEntering && "animate-in fade-in duration-150 motion-reduce:animate-none",
          isExiting && "animate-out fade-out duration-100 motion-reduce:animate-none"
        )
      }
    >
      <Modal
        className={({ isEntering, isExiting }) =>
          cn(
            "flex max-h-[calc(100dvh-2rem)] w-full flex-col overflow-visible text-card-foreground outline-none sm:max-h-[88dvh]",
            sizeClass[size],
            "motion-reduce:transition-none",
            isEntering && "animate-in slide-in-from-bottom-3 zoom-in-95 duration-150 motion-reduce:animate-none",
            isExiting && "animate-out slide-out-to-bottom-2 zoom-out-95 duration-100 motion-reduce:animate-none"
          )
        }
      >
        <DialogPortalContext.Provider value={portalContainer}>
          <Dialog
            role={role}
            aria-labelledby={titleId}
            aria-describedby={description ? descriptionId : undefined}
            className="flex min-h-0 flex-1 flex-col overflow-hidden rounded-card border border-border bg-card shadow-2xl outline-none"
          >
            {({ close }) => (
              <>
              <header className="flex shrink-0 items-start gap-3 border-b border-border px-5 py-4">
                <div className={cn("mt-0.5 grid size-9 shrink-0 place-items-center rounded-field border", toneClass[tone])}>
                  {icon ?? <ToneIcon className="size-5" aria-hidden="true" />}
                </div>
                <div className="min-w-0 flex-1">
                  <Heading id={titleId} slot="title" className="text-base font-semibold text-foreground">
                    {title}
                  </Heading>
                  {description ? (
                    <p id={descriptionId} className="mt-1 text-sm leading-relaxed text-muted-foreground">
                      {description}
                    </p>
                  ) : null}
                </div>
                {dismissable ? (
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon"
                    onClick={close}
                    aria-label={t("action.close")}
                    className="size-8 shrink-0 text-muted-foreground hover:text-foreground"
                  >
                    <X className="size-4" aria-hidden="true" />
                  </Button>
                ) : null}
              </header>
              {children ? <div className="min-h-0 flex-1 overflow-y-auto px-5 py-4">{children}</div> : null}
              {footer ? <footer className="sticky bottom-0 flex shrink-0 justify-end gap-2 border-t border-border bg-card px-5 py-4">{footer}</footer> : null}
              </>
            )}
          </Dialog>
          <div ref={setPortalContainer} className="contents" />
        </DialogPortalContext.Provider>
      </Modal>
    </ModalOverlay>
  );
}
