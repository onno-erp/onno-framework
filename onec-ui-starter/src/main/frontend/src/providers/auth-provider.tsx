import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from "react";
import { api, setUnauthorizedHandler } from "@/lib/api";
import type { AuthUser } from "@/lib/types";

// Guards the OIDC silent re-auth redirect against loops: if a round-trip comes back still
// anonymous (e.g. IdP misconfig), we don't immediately redirect again.
const REAUTH_GUARD_KEY = "onec:last-silent-reauth";
const REAUTH_GUARD_MS = 30_000;

/**
 * Attempt a silent OIDC re-authentication by navigating to the authorization endpoint. When the
 * IdP's SSO session is still alive this round-trip is invisible and the SPA reloads already signed
 * in; otherwise the IdP shows its own login (unavoidable once both the app session and the SSO
 * session are gone). Returns true if a redirect was issued. Time-guarded so it can't loop.
 */
function trySilentReauth(loginUrl: string): boolean {
  try {
    const last = Number(sessionStorage.getItem(REAUTH_GUARD_KEY) ?? "0");
    if (Date.now() - last < REAUTH_GUARD_MS) return false;
    sessionStorage.setItem(REAUTH_GUARD_KEY, String(Date.now()));
  } catch {
    // sessionStorage unavailable — proceed without the guard rather than blocking recovery.
  }
  window.location.href = loginUrl;
  return true;
}

interface AuthContextValue {
  user: AuthUser | null;
  loading: boolean;
  // Present in OIDC mode: the URL the login screen redirects to (Keycloak). Null otherwise.
  loginUrl: string | null;
  // Present in OIDC mode: the URL logout redirects to (ends the Keycloak session). Null otherwise.
  logoutUrl: string | null;
  login: (username: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
  refresh: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<AuthUser | null>(null);
  const [loginUrl, setLoginUrl] = useState<string | null>(null);
  const [logoutUrl, setLogoutUrl] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  // Whether a live session has existed in this tab. Distinguishes "not signed in yet" (first
  // load → show the login screen) from "the session lapsed" (→ try to recover silently).
  const wasAuthenticatedRef = useRef(false);

  // A session we once had has gone anonymous. In OIDC mode, bounce through the IdP to re-auth
  // against its still-alive SSO session (invisible when it works); in in-memory mode the
  // remember-me cookie would already have re-authenticated the /api/auth/me call, so reaching
  // here means it's truly gone — fall back to the login screen.
  const handleSessionLost = useCallback((url: string | null) => {
    if (wasAuthenticatedRef.current && url && trySilentReauth(url)) {
      return;
    }
    wasAuthenticatedRef.current = false;
    setUser(null);
  }, []);

  const refresh = useCallback(async () => {
    try {
      const currentUser = await api.getCurrentUser();
      // loginUrl/logoutUrl are reported whether or not authenticated, so the UI can offer the
      // right affordance (Keycloak redirect vs. password form) even before sign-in.
      setLoginUrl(currentUser.loginUrl ?? null);
      setLogoutUrl(currentUser.logoutUrl ?? null);
      if (currentUser.authenticated) {
        wasAuthenticatedRef.current = true;
        // A fresh session clears the re-auth guard so a later lapse can redirect again.
        try { sessionStorage.removeItem(REAUTH_GUARD_KEY); } catch { /* ignore */ }
        setUser(currentUser);
      } else {
        handleSessionLost(currentUser.loginUrl ?? null);
      }
    } catch {
      // Network or unexpected error — treat as signed out, but don't redirect (likely transient).
      setUser(null);
    } finally {
      setLoading(false);
    }
  }, [handleSessionLost]);

  useEffect(() => {
    refresh();
  }, [refresh]);

  // A 401 on any data call (not just the heartbeat) routes through here, so a lapsed session is
  // recovered the instant the user acts, not only on the next 4-minute poll.
  useEffect(() => {
    setUnauthorizedHandler(() => {
      void refresh();
    });
    return () => setUnauthorizedHandler(null);
  }, [refresh]);

  // Keep the server session alive while a tab is open, and recover the moment it isn't. The
  // app's only standing connection is the SSE stream, which is dispatched once and so never
  // bumps the session's last-accessed time — without this a parked tab silently loses its
  // session after the idle timeout and the next action fails with no explanation. A lightweight
  // /api/auth/me poll refreshes the session and, when it has lapsed, drives the recovery in
  // refresh() (silent IdP re-auth in OIDC mode, remember-me re-auth in in-memory mode).
  const HEARTBEAT_MS = 4 * 60 * 1000;
  useEffect(() => {
    if (!user) return;
    const beat = () => {
      if (document.visibilityState === "visible") refresh();
    };
    const interval = window.setInterval(beat, HEARTBEAT_MS);
    // Revalidate immediately on regaining focus (e.g. waking from sleep) so a session that
    // expired while the tab was hidden surfaces as the login screen, not a broken panel.
    document.addEventListener("visibilitychange", beat);
    return () => {
      window.clearInterval(interval);
      document.removeEventListener("visibilitychange", beat);
    };
  }, [user, refresh]);

  const login = useCallback(async (username: string, password: string) => {
    try {
      const currentUser = await api.login(username, password);
      wasAuthenticatedRef.current = true;
      setUser(currentUser);
    } catch (err) {
      setUser(null);
      throw err;
    }
  }, []);

  const logout = useCallback(async () => {
    // Mark intentional sign-out first so a subsequent anonymous /api/auth/me doesn't trigger a
    // silent re-auth redirect — logout should land on the login screen, not bounce back in.
    wasAuthenticatedRef.current = false;
    try { sessionStorage.removeItem(REAUTH_GUARD_KEY); } catch { /* ignore */ }
    // OIDC mode: logout is a full-page navigation to the RP-initiated-logout endpoint, which
    // clears the local session and bounces through Keycloak's end-session endpoint before
    // returning to the SPA. A fetch can't follow that cross-origin redirect, so we navigate.
    if (logoutUrl) {
      window.location.href = logoutUrl;
      return;
    }
    try {
      await api.logout();
    } finally {
      setUser(null);
    }
  }, [logoutUrl]);

  const value = useMemo(
    () => ({ user, loading, loginUrl, logoutUrl, login, logout, refresh }),
    [user, loading, loginUrl, logoutUrl, login, logout, refresh]
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error("useAuth must be used inside AuthProvider");
  }
  return context;
}
