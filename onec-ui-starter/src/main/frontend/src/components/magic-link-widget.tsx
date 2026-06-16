import { FormEvent, useState } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { api } from "@/lib/api";

/**
 * The React widget behind the DivKit {@code onec-magic-link} custom block — the passwordless
 * sign-in option on the server-driven login screen. Like the password sub-form, it needs to read a
 * typed value (the email) on submit, which a DivKit action can't do, so it's a real React form. It
 * asks the server to email a single-use link, then shows a neutral confirmation. The server answers
 * identically whether or not the address is registered, so this UI never reveals which emails exist.
 */
export function MagicLinkWidget() {
  const [email, setEmail] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [sent, setSent] = useState(false);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSubmitting(true);
    try {
      await api.requestMagicLink(email);
    } catch {
      // Show the same confirmation even on an unexpected error — never signal whether the address
      // matched an account. (Network failures are surfaced separately by the api layer.)
    } finally {
      setSubmitting(false);
      setSent(true);
    }
  }

  // The login card's container blocks carry pointer-events:none (taps fall through to DivKit
  // actions); re-enable them here so the input/button work, and inset from the clipping block edge.
  if (sent) {
    return (
      <p className="px-2 pointer-events-auto text-sm text-muted-foreground">
        If an account matches that address, a sign-in link is on its way — check your inbox.
      </p>
    );
  }

  return (
    <form onSubmit={handleSubmit} className="space-y-3 px-2 pointer-events-auto">
      <div className="space-y-2">
        <Label htmlFor="magic-email">Or get a sign-in link by email</Label>
        <Input
          id="magic-email"
          type="email"
          autoComplete="email"
          placeholder="you@example.com"
          value={email}
          onChange={(event) => setEmail(event.target.value)}
        />
      </div>
      <Button className="w-full" type="submit" variant="outline" disabled={submitting || !email}>
        {submitting ? "Sending…" : "Email me a link"}
      </Button>
    </form>
  );
}
