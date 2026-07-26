import { useEffect, useState } from "react";
import { toast } from "sonner";
import { DialogShell } from "@/components/ui/dialog-shell";
import { Button } from "@/components/ui/button";
import { ApiError } from "@/lib/api";
import type { ActionFeedback, ActionResult } from "@/lib/types";
import { useMessages } from "@/providers/messages-provider";

const EVENT = "onno:action-feedback";

function toastFor(feedback: ActionFeedback) {
  const details = feedback.details ?? [];
  const title = feedback.title || feedback.message || "Done";
  const description = ((feedback.title && feedback.message) || details.length) ? (
    <div className="onno-toast__body">
      {feedback.title && feedback.message ? (
        <p className="onno-toast__message">{feedback.message}</p>
      ) : null}
      {details.length ? (
        <ul className="onno-toast__details">
          {details.map((detail, index) => (
            <li key={`${index}-${detail}`}>
              <span aria-hidden="true" />
              {detail}
            </li>
          ))}
        </ul>
      ) : null}
    </div>
  ) : undefined;

  let id: string | number;
  const options = {
    description,
    action: feedback.dismissLabel ? {
      label: feedback.dismissLabel,
      onClick: () => toast.dismiss(id),
    } : undefined,
  };
  if (feedback.severity === "success") id = toast.success(title, options);
  else if (feedback.severity === "warning") id = toast.warning(title, options);
  else if (feedback.severity === "error") id = toast.error(title, options);
  else id = toast.info(title, options);
}

/** Present typed feedback consistently. Inline feedback without an active form falls back to a dialog. */
export function presentActionFeedback(feedback: ActionFeedback) {
  if (feedback.presentation === "toast") {
    toastFor(feedback);
    return;
  }
  window.dispatchEvent(new CustomEvent<ActionFeedback>(EVENT, { detail: feedback }));
}

/** Apply the common ActionResult contract at every action entry point. */
export function applyActionResult(
  result: ActionResult | undefined,
  callbacks: { navigate?: (url: string) => void; refresh?: () => void } = {}
) {
  if (!result) return;
  if (result.feedback) presentActionFeedback(result.feedback);
  else if (result.message) toast.success(result.message);
  if (result.navigate) callbacks.navigate?.(result.navigate);
  if (result.refresh) callbacks.refresh?.();
}

/** Present an expected action rejection; returns it for an open form to render inline. */
export function actionFeedbackFromError(error: unknown, inline = false): ActionFeedback | undefined {
  if (error instanceof ApiError && error.feedback) {
    const hasFormErrors =
      (error.feedback.formErrors?.length ?? 0) > 0 ||
      Object.keys(error.feedback.fieldErrors ?? {}).length > 0;
    if (!inline || (!hasFormErrors && error.feedback.presentation !== "inline")) {
      presentActionFeedback(error.feedback);
    }
    return error.feedback;
  }
  return undefined;
}

/** One global host for typed action-result/rejection dialogs. */
export function ActionFeedbackHost() {
  const t = useMessages();
  const [feedback, setFeedback] = useState<ActionFeedback | null>(null);
  useEffect(() => {
    const listener = (event: Event) => setFeedback((event as CustomEvent<ActionFeedback>).detail);
    window.addEventListener(EVENT, listener);
    return () => window.removeEventListener(EVENT, listener);
  }, []);

  if (!feedback) return null;
  const details = feedback.details ?? [];
  return (
    <DialogShell
      role={feedback.severity === "error" ? "alertdialog" : "dialog"}
      title={feedback.title || t(feedback.severity === "error" ? "action.feedback.blocked" : "action.feedback.completed")}
      description={feedback.message}
      tone={feedback.severity}
      size="md"
      onOpenChange={(open) => {
        if (!open) setFeedback(null);
      }}
      footer={
        <Button
          type="button"
          autoFocus
          onClick={() => setFeedback(null)}
        >
          {feedback.dismissLabel || t("action.ok")}
        </Button>
      }
    >
      {details.length ? (
        <ul className="space-y-2 text-sm text-foreground">
          {details.map((detail, index) => (
            <li key={`${index}-${detail}`} className="rounded-field border border-border bg-muted/30 px-3 py-2">
              {detail}
            </li>
          ))}
        </ul>
      ) : null}
    </DialogShell>
  );
}
