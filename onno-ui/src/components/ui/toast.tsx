import * as React from "react";
import {
  CircleCheck,
  CircleX,
  Info,
  LoaderCircle,
  TriangleAlert,
} from "lucide-react";
import { Toast as ToastPrimitive } from "@base-ui/react/toast";

type ToastKind = "default" | "success" | "info" | "warning" | "error" | "loading";

type ToastButton = {
  label: React.ReactNode;
  onClick?: () => void;
};

export type ToastOptions = {
  id?: string | number;
  description?: React.ReactNode;
  duration?: number;
  action?: ToastButton;
  cancel?: ToastButton;
};

type ToastData = {
  cancel?: ToastButton;
};

const toastManager = ToastPrimitive.createToastManager<ToastData>();

function timeoutOf(duration: number | undefined): number | undefined {
  if (duration === Number.POSITIVE_INFINITY) return 0;
  return duration;
}

function publish(kind: ToastKind, title: React.ReactNode, options: ToastOptions = {}): string {
  return toastManager.add({
    id: options.id === undefined ? undefined : String(options.id),
    title,
    type: kind,
    description: options.description,
    timeout: timeoutOf(options.duration),
    priority: kind === "error" ? "high" : "low",
    actionProps: options.action
      ? {
          children: options.action.label,
          onClick: options.action.onClick,
        }
      : undefined,
    data: { cancel: options.cancel },
  });
}

type ToastApi = {
  (title: React.ReactNode, options?: ToastOptions): string;
  success(title: React.ReactNode, options?: ToastOptions): string;
  info(title: React.ReactNode, options?: ToastOptions): string;
  warning(title: React.ReactNode, options?: ToastOptions): string;
  error(title: React.ReactNode, options?: ToastOptions): string;
  loading(title: React.ReactNode, options?: ToastOptions): string;
  dismiss(id?: string | number): void;
};

export const toast: ToastApi = Object.assign(
  (title: React.ReactNode, options?: ToastOptions) => publish("default", title, options),
  {
    success: (title: React.ReactNode, options?: ToastOptions) => publish("success", title, options),
    info: (title: React.ReactNode, options?: ToastOptions) => publish("info", title, options),
    warning: (title: React.ReactNode, options?: ToastOptions) => publish("warning", title, options),
    error: (title: React.ReactNode, options?: ToastOptions) => publish("error", title, options),
    loading: (title: React.ReactNode, options?: ToastOptions) => publish("loading", title, options),
    dismiss: (id?: string | number) => toastManager.close(id === undefined ? undefined : String(id)),
  }
);

function ToastIcon({ type }: { type?: string }) {
  switch (type) {
    case "success":
      return <CircleCheck />;
    case "warning":
      return <TriangleAlert />;
    case "error":
      return <CircleX />;
    case "loading":
      return <LoaderCircle className="onno-toast__spinner" />;
    default:
      return <Info />;
  }
}

function ToastList() {
  const { toasts } = ToastPrimitive.useToastManager<ToastData>();

  return toasts.map((item) => (
    <ToastPrimitive.Root
      key={item.id}
      toast={item}
      swipeDirection={["right", "down"]}
      className="onno-toast"
    >
      <ToastPrimitive.Content className="onno-toast__content">
        <span className="onno-toast__icon" aria-hidden="true">
          <ToastIcon type={item.type} />
        </span>
        <div className="onno-toast__copy">
          <ToastPrimitive.Title className="onno-toast__title" />
          {item.description ? (
            <ToastPrimitive.Description
              className="onno-toast__description"
              render={<div />}
            />
          ) : null}
        </div>
        {item.actionProps || item.data?.cancel ? (
          <div className="onno-toast__actions">
            {item.actionProps ? (
              <ToastPrimitive.Action className="onno-toast__action" />
            ) : null}
            {item.data?.cancel ? (
              <ToastPrimitive.Close
                className="onno-toast__cancel"
                onClick={item.data.cancel.onClick}
              >
                {item.data.cancel.label}
              </ToastPrimitive.Close>
            ) : null}
          </div>
        ) : null}
      </ToastPrimitive.Content>
    </ToastPrimitive.Root>
  ));
}

/**
 * Canonical shadcn-style toast host backed by Base UI. Base UI owns the manager, accessibility,
 * focus, swipe dismissal, stack geometry, and transition state; onno only supplies the renderer.
 */
export function Toaster() {
  return (
    <ToastPrimitive.Provider toastManager={toastManager} timeout={5_500} limit={5}>
      <ToastPrimitive.Portal>
        <ToastPrimitive.Viewport className="onno-toaster">
          <ToastList />
        </ToastPrimitive.Viewport>
      </ToastPrimitive.Portal>
    </ToastPrimitive.Provider>
  );
}
