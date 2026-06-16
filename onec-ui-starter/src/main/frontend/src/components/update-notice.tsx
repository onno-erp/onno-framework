import { useEffect } from "react";
import { toast } from "sonner";
import { api } from "@/lib/api";

// Remembers the version a user already dismissed, so the notice doesn't nag on every load but
// reappears once an even newer version ships.
const DISMISSED_KEY = "onec.update.dismissed";
const TOAST_ID = "onec-update";

function dismissed(): string | null {
  try {
    return localStorage.getItem(DISMISSED_KEY);
  } catch {
    return null;
  }
}

function remember(version: string) {
  try {
    localStorage.setItem(DISMISSED_KEY, version);
  } catch {
    // Private mode / storage disabled — worst case the notice shows again next load.
  }
}

/**
 * Renders nothing; on mount it asks the server (which polls onec-cloud) whether a newer framework
 * version exists and, if so, raises a persistent dismissible toast. Mounted once inside the
 * authenticated app. Fail-quiet: any error from the config fetch is ignored — a version notice is
 * never worth interrupting the app for.
 */
export function UpdateNotice() {
  useEffect(() => {
    let cancelled = false;

    api
      .getConfig()
      .then((config) => {
        const update = config.update;
        if (cancelled || !update?.available || !update.latest) return;
        if (dismissed() === update.latest) return;

        const running = update.current ? ` You're running ${update.current}.` : "";
        toast(`onec ${update.latest} is available.`, {
          id: TOAST_ID,
          description: `A newer version of the framework has been released.${running}`,
          duration: Infinity,
          action: update.url
            ? {
                label: "Release notes",
                onClick: () => window.open(update.url!, "_blank", "noopener,noreferrer"),
              }
            : undefined,
          cancel: {
            label: "Dismiss",
            onClick: () => remember(update.latest!),
          },
        });
      })
      .catch(() => {
        // No config / network error — stay silent.
      });

    return () => {
      cancelled = true;
    };
  }, []);

  return null;
}
