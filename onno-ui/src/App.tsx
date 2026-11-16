import { useEffect, type ReactNode } from "react";
import { QueryClientProvider } from "@tanstack/react-query";
import { queryClient, startQueryInvalidation } from "./lib/query";
import { BrowserRouter, Navigate, Routes, Route, useLocation } from "react-router";
import { api } from "./lib/api";
import { ThemeProvider } from "./providers/theme-provider";
import { BrandingProvider } from "./providers/branding-provider";
import { MessagesProvider, useMessages } from "./providers/messages-provider";
import { AuthProvider, useAuth } from "./providers/auth-provider";
import { TimeRangeProvider } from "./providers/time-range-provider";
import { LoginView } from "./views/login";
import { ChangePasswordView } from "./views/change-password";
import { AppShell } from "./views/app-shell";
import { NotificationCenter } from "./components/collaboration/notification-center";
import { NotificationsProvider } from "./lib/notification-store";
import { PresenceProvider } from "./lib/presence-store";
import { DirtyFormsProvider } from "./lib/dirty-forms";
import { QuickCreateProvider } from "./lib/quick-create";
import { UpdateNotice } from "./components/shell/update-notice";
import { Toaster } from "./components/ui/toast";
import { BASE_PATH } from "./lib/base-path";
import { CommandFeedbackProvider } from "./lib/command-feedback";
import { configureTelemetry, recordRouteView } from "./lib/telemetry";
import { AppConfigProvider, type AppDefinition } from "./lib/app-config";

function ProtectedApp() {
  const { user, loading } = useAuth();
  const location = useLocation();
  const t = useMessages();

  // A one-time-password session may only change its password: the server refuses every other API
  // call, so don't boot the shell for it.
  const authenticated = !!user && !user.passwordChangeRequired;

  useEffect(() => {
    if (!authenticated) return;
    let cancelled = false;
    api
      .getConfig()
      .then((cfg) => {
        if (!cancelled) configureTelemetry(cfg?.telemetry);
      })
      .catch(() => {});
    return () => {
      cancelled = true;
    };
  }, [authenticated]);

  useEffect(() => {
    recordRouteView(location.pathname);
  }, [location.pathname]);

  // Server change events invalidate the matching TanStack queries (app data stays live).
  useEffect(() => (authenticated ? startQueryInvalidation() : undefined), [authenticated]);

  if (loading) {
    return (
      <div className="flex h-screen w-screen items-center justify-center bg-background text-sm text-muted-foreground">
        {t("loading.workspace")}
      </div>
    );
  }

  if (!user) {
    return <Navigate to="/login" replace state={{ from: location }} />;
  }

  if (user.passwordChangeRequired) {
    return <ChangePasswordView />;
  }

  // The authenticated app: a React shell built from the business model, rendering the app's own
  // pages or the model's default surfaces.
  return (
    <TimeRangeProvider>
      <NotificationsProvider>
        <PresenceProvider>
          <UpdateNotice />
          <NotificationCenter />
          <CommandFeedbackProvider>
            <DirtyFormsProvider>
              <QuickCreateProvider>
                <AppShell />
              </QuickCreateProvider>
            </DirtyFormsProvider>
          </CommandFeedbackProvider>
        </PresenceProvider>
      </NotificationsProvider>
    </TimeRangeProvider>
  );
}

function WorkspaceProviders({ children }: { children: ReactNode }) {
  return (
    <QueryClientProvider client={queryClient}>
    <ThemeProvider>
      <BrandingProvider>
        <MessagesProvider>
          <AuthProvider>
            {children}
            <Toaster />
          </AuthProvider>
        </MessagesProvider>
      </BrandingProvider>
    </ThemeProvider>
    </QueryClientProvider>
  );
}

/** The whole UI: login, then the shell with the app's declaration (pages, navigation, entities). */
export default function App({ app = {} }: { app?: AppDefinition }) {
  return (
    <BrowserRouter basename={BASE_PATH}>
      <Routes>
        <Route path="/login" element={<WorkspaceProviders><LoginView /></WorkspaceProviders>} />
        <Route path="*" element={<WorkspaceProviders><AppConfigProvider app={app}><ProtectedApp /></AppConfigProvider></WorkspaceProviders>} />
      </Routes>
    </BrowserRouter>
  );
}
