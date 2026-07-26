import {
  CircleCheck,
  CircleX,
  Info,
  LoaderCircle,
  TriangleAlert,
} from "lucide-react";
import type { CSSProperties } from "react";
import { Toaster } from "sonner";
import { useTheme } from "@/providers/theme-provider";

/**
 * The one application toast host. Sonner owns queueing, focus, swipe-to-dismiss, stack geometry,
 * and motion; the onno classes in index.css only own its theme and shape.
 */
export function AppToaster() {
  const { theme } = useTheme();

  return (
    <Toaster
      theme={theme}
      position="bottom-right"
      expand={false}
      richColors={false}
      visibleToasts={5}
      gap={12}
      duration={5_500}
      offset={{ right: 20, bottom: 20 }}
      mobileOffset={{ right: 12, bottom: 12, left: 12 }}
      swipeDirections={["right", "bottom"]}
      className="onno-toaster"
      style={{ "--width": "440px" } as CSSProperties}
      toastOptions={{
        className: "onno-toast",
        classNames: {
          title: "onno-toast__title",
          description: "onno-toast__description",
          content: "onno-toast__content",
          icon: "onno-toast__icon",
          actionButton: "onno-toast__action",
          cancelButton: "onno-toast__cancel",
        },
      }}
      icons={{
        success: <CircleCheck />,
        info: <Info />,
        warning: <TriangleAlert />,
        error: <CircleX />,
        loading: <LoaderCircle className="onno-toast__spinner" />,
      }}
    />
  );
}
