import { toast } from "../components/ui/toast";
import type { CommandFeedback } from "./types";

// Every feedback object shown so far, by identity: a repeated report of the same outcome adds
// nothing, while the next command's outcome is a new object and shows again.
const shown = new WeakSet<CommandFeedback>();

/**
 * Claim `feedback` for display: true the first time, false once it has been shown (as a toast, in
 * the dialog, or inline in an open form). Whoever shows feedback claims it first.
 */
export function claimFeedback(feedback: CommandFeedback): boolean {
  if (shown.has(feedback)) return false;
  shown.add(feedback);
  return true;
}

/**
 * Show feedback as a toast of its kind: the title (else the message) over the message, then the
 * input errors and details as a list. A rejection that names only input errors is headed by the
 * first of them, so a toast is never left without the server's words.
 */
export function toastFeedback(feedback: CommandFeedback): void {
  const inputErrors = [...(feedback.formErrors ?? []), ...Object.values(feedback.fieldErrors ?? {}).flat()];
  const heading = feedback.title || feedback.message;
  const title = heading || inputErrors[0] || "Done";
  const message = feedback.title ? feedback.message : undefined;
  const details = [...(heading ? inputErrors : inputErrors.slice(1)), ...(feedback.details ?? [])];
  const description = message || details.length ? (
    <div className="onno-toast__body">
      {message ? <p className="onno-toast__message">{message}</p> : null}
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
  const options = { description };
  if (feedback.severity === "success") toast.success(title, options);
  else if (feedback.severity === "warning") toast.warning(title, options);
  else if (feedback.severity === "error") toast.error(title, options);
  else toast.info(title, options);
}
