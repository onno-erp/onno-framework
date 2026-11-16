import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { useState } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import App from "@/App";
import { LoginLayout, SsoButtons, useSignIn, type LoginPageProps } from "@/components/auth/sign-in";

type Call = { url: string; method: string; body: unknown };

/** A login page asking for an e-mailed code — the shape `defineApp({ login })` is for. */
function CodeLogin({ methods, error }: LoginPageProps) {
  const signIn = useSignIn();
  const [sentTo, setSentTo] = useState<string | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  return (
    <LoginLayout title="Bookstore" error={problem ?? error}>
      {sentTo ? <p>Code sent to {sentTo}</p> : null}
      <button onClick={() => signIn.challenge<{ sentTo: string }>("code", { email: "a@x" }).then((r) => setSentTo(r.sentTo))}>
        Send code
      </button>
      <button onClick={() => signIn.method("code", { email: "a@x", code: "0000" }).catch((e: Error) => setProblem(e.message))}>
        Wrong code
      </button>
      <button onClick={() => void signIn.method("code", { email: "a@x", code: "1234" })}>Right code</button>
      {methods ? <SsoButtons providers={methods.providers} /> : null}
    </LoginLayout>
  );
}

let calls: Call[];

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

beforeEach(() => {
  calls = [];
  window.history.pushState({}, "", "/login");
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    const method = init?.method ?? "GET";
    calls.push({ url, method, body: init?.body ? JSON.parse(String(init.body)) : undefined });
    if (url.endsWith("/api/auth/me")) return json(200, { authenticated: false, username: "", roles: [], mode: "database" });
    if (url.endsWith("/api/ui/login")) {
      return json(200, {
        passwordEnabled: true, mode: "database", demoAccounts: [],
        providers: [{ id: "keycloak", label: "Continue with Keycloak", url: "/oauth2/authorization/keycloak" }],
      });
    }
    if (url.endsWith("/api/auth/login/code/challenge")) return json(200, { sentTo: "a•@x" });
    if (url.endsWith("/api/auth/login/code")) {
      const body = JSON.parse(String(init?.body));
      return body.code === "1234"
        ? json(200, { authenticated: true, username: "a@x", roles: ["ROLE_MANAGER"], mode: "database" })
        : json(401, { message: "The code is wrong or has expired" });
    }
    return json(200, {});
  }));
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("an app's own login page", () => {
  it("replaces the default screen and drives an application login method", async () => {
    render(<App app={{ login: CodeLogin }} />);

    expect(await screen.findByText("Bookstore")).toBeTruthy();
    expect(await screen.findByRole("button", { name: "Continue with Keycloak" })).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: "Send code" }));
    expect(await screen.findByText("Code sent to a•@x")).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: "Wrong code" }));
    expect((await screen.findByRole("alert")).textContent).toBe("The code is wrong or has expired");
    expect(window.location.pathname).toBe("/login");

    fireEvent.click(screen.getByRole("button", { name: "Right code" }));
    await waitFor(() => expect(window.location.pathname).toBe("/"));
    expect(calls.filter((c) => c.method === "POST").map((c) => [c.url, c.body])).toEqual([
      ["/api/auth/login/code/challenge", { email: "a@x" }],
      ["/api/auth/login/code", { email: "a@x", code: "0000" }],
      ["/api/auth/login/code", { email: "a@x", code: "1234" }],
    ]);
  });

  it("keeps the framework's login page when the app supplies none", async () => {
    render(<App app={{}} />);

    expect(await screen.findByRole("heading", { name: "Sign in" })).toBeTruthy();
    expect(await screen.findByRole("button", { name: "Continue with Keycloak" })).toBeTruthy();
  });
});
