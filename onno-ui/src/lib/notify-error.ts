import { toast } from "../components/ui/toast";
import { ApiError, isSessionLapse } from "./api";
import { claimFeedback, toastFeedback } from "./feedback-toast";

export interface NotifyErrorOptions {
  /** Shown when the failure carries no message of its own (not an Error, or an empty one). */
  fallback?: string;
  /** Wraps the message with the caller's context, e.g. `m => \`Couldn't add: ${m}\``. */
  format?: (message: string) => string;
}

/** The text a caught failure shows: its own message, else `fallback`, else the value itself. */
export function errorMessage(error: unknown, fallback?: string): string {
  return error instanceof Error && error.message ? error.message : fallback ?? String(error);
}

/**
 * Show a failed user action to the user exactly once (#505). Call it from a catch block that ends a
 * user action, instead of calling `toast.error` there yourself:
 *
 * - an {@link ApiError} the api layer already toasted (`notified`) is skipped;
 * - a lapsed session (401) is skipped: the auth recovery takes over;
 * - typed feedback becomes a toast of its kind under its own title — the server's wording, so
 *   `format` and `fallback` do not apply;
 * - anything else becomes one error toast: a call made with `{ silent: true }` so the caller could
 *   word the message itself, or a failure the api layer leaves to its caller — a network error or
 *   a validation error with field detail.
 *
 * Callers that want their own wording pass `{ silent: true }` to the api call and a `format` here;
 * callers that just surface the server's message pass at most a `fallback`.
 */
export function notifyError(error: unknown, options: NotifyErrorOptions = {}): void {
  if (error instanceof ApiError) {
    if (error.notified) return;
    error.notified = true;
    if (isSessionLapse(error)) return;
    if (error.feedback) {
      if (claimFeedback(error.feedback)) toastFeedback(error.feedback);
      return;
    }
  }
  const message = errorMessage(error, options.fallback);
  toast.error(options.format ? options.format(message) : message);
}
