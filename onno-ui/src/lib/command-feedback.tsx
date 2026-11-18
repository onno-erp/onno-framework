import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from "react";
import { DialogShell } from "../components/ui/dialog-shell";
import { Button } from "../components/ui/button";
import { ApiError, isSessionLapse } from "./api";
import { claimFeedback, toastFeedback } from "./feedback-toast";
import { notifyError as notifyFailure } from "./notify-error";
import type { CommandFeedback, CommandResult } from "./types";
import { isNavigationTarget, openRoute } from "./navigation";
import { useMessages } from "../providers/messages-provider";

/**
 * Whether feedback needs acknowledging in a dialog rather than a passing toast: an error, or
 * anything with detail lines (a list is not read in a toast's few seconds), or input errors.
 */
export function feedbackNeedsDialog(feedback: CommandFeedback): boolean {
  return (
    feedback.severity === "error" ||
    (feedback.details?.length ?? 0) > 0 ||
    (feedback.formErrors?.length ?? 0) > 0 ||
    Object.keys(feedback.fieldErrors ?? {}).length > 0
  );
}

/** What a component does with a command's outcome; read with {@link useCommandFeedback}. */
export type CommandFeedbackHandlers = {
  /**
   * Show a command's feedback. The server sends meaning (severity, message, details); the client
   * decides display: short success/info/warning → a toast; errors and detailed feedback → a dialog.
   * The same feedback object is shown once, however many times it is presented.
   */
  present: (feedback: CommandFeedback) => void;
  /** Apply a {@link CommandResult}: present its feedback, call `refresh` when the server asks, and open its `navigateTo`. */
  applyResult: (result: CommandResult | undefined, callbacks?: { refresh?: () => void }) => void;
  /**
   * Handle an expected command rejection (HTTP 422 with feedback) and return it. With `inForm` the
   * caller's open input form shows it (and stays open with the user's values); otherwise it is
   * presented here. Either way the error counts as shown, so a later `notifyError` adds nothing.
   */
  fromError: (error: unknown, inForm?: boolean) => CommandFeedback | undefined;
  /**
   * Report a failed command, post or delete exactly once: typed feedback is presented the way a
   * result's is (an error opens the dialog), anything else becomes one error toast through
   * `notifyError`, and nothing is added when the api layer has shown the failure already or the
   * session has lapsed (401, left to the auth recovery).
   */
  notifyError: (error: unknown) => void;
};

/** `show` displays feedback; the returned handlers show each feedback object at most once. */
function handlers(show: (feedback: CommandFeedback) => void): CommandFeedbackHandlers {
  const present = (feedback: CommandFeedback) => {
    if (claimFeedback(feedback)) show(feedback);
  };
  return {
    present,
    applyResult: (result, callbacks = {}) => {
      if (!result) return;
      if (result.feedback) present(result.feedback);
      if (result.refresh) callbacks.refresh?.();
      if (result.navigateTo && isNavigationTarget(result.navigateTo)) openRoute(result.navigateTo);
    },
    fromError: (error, inForm = false) => {
      if (error instanceof ApiError && error.feedback && !isSessionLapse(error)) {
        if (!error.notified) {
          error.notified = true;
          // The open form shows it inline; claim it so nothing presents it a second time.
          if (inForm) claimFeedback(error.feedback);
          else present(error.feedback);
        }
        return error.feedback;
      }
      return undefined;
    },
    notifyError: (error) => {
      if (error instanceof ApiError && error.feedback && !isSessionLapse(error)) {
        if (!error.notified) {
          error.notified = true;
          present(error.feedback);
        }
        return;
      }
      notifyFailure(error);
    },
  };
}

/** Outside a provider (a component rendered on its own): every outcome is a toast. */
const CommandFeedbackContext = createContext<CommandFeedbackHandlers>(handlers(toastFeedback));

/** How this component shows command outcomes: toasts, and the provider's dialog for errors and details. */
export function useCommandFeedback(): CommandFeedbackHandlers {
  return useContext(CommandFeedbackContext);
}

/** Holds the app's one command-feedback dialog; mounted by the authenticated app. */
export function CommandFeedbackProvider({ children }: { children: ReactNode }) {
  const t = useMessages();
  const [feedback, setFeedback] = useState<CommandFeedback | null>(null);
  const show = useCallback((next: CommandFeedback) => {
    if (feedbackNeedsDialog(next)) setFeedback(next);
    else toastFeedback(next);
  }, []);
  const value = useMemo(() => handlers(show), [show]);
  const details = feedback?.details ?? [];

  return (
    <CommandFeedbackContext.Provider value={value}>
      {children}
      {feedback ? (
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
            <Button type="button" autoFocus onClick={() => setFeedback(null)}>
              {t("action.ok")}
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
      ) : null}
    </CommandFeedbackContext.Provider>
  );
}
