import {
  CircleCheck,
  CircleX,
  Info,
  LoaderCircle,
  TriangleAlert,
} from "lucide-react";
import { Toaster } from "sonner";
import { useTheme } from "@/providers/theme-provider";

/**
 * The one application toast host. Sonner owns queueing, focus, swipe-to-dismiss, and stack
 * geometry; the onno classes in index.css own its theme, shape, and motion.
 */
export function AppToaster() {
  const { theme } = useTheme();

  return (
    <Toaster
      theme={theme}
      position="bottom-right"
      expand={false}
      richColors={false}
      visibleToasts={4}
      gap={10}
      duration={4_000}
      offset={{ right: 16, bottom: 16 }}
      mobileOffset={{ right: 12, bottom: 12, left: 12 }}
      swipeDirections={["right", "bottom"]}
      className="onno-toaster"
      toastOptions={{
        className: "t-toast onno-toast",
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
