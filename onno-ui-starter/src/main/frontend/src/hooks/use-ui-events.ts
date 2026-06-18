import { useEffect } from "react";
import { streamUiEvents } from "@/lib/api";
import type { UiEvent } from "@/lib/types";

export function useUiEvents(handler: (event: UiEvent) => void) {
  useEffect(() => {
    let stopped = false;
    let retry: number | undefined;
    const controller = new AbortController();

    async function connect() {
      try {
        await streamUiEvents((event) => {
          if (!stopped) handler(event);
        }, controller.signal);
      } catch {
        if (!stopped) {
          retry = window.setTimeout(connect, 3000);
        }
      }
    }

    connect();
    return () => {
      stopped = true;
      controller.abort();
      if (retry) window.clearTimeout(retry);
    };
  }, [handler]);
}
