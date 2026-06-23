import type { ReactNode } from "react";
import { BrowserRouter, Navigate, Routes, Route, useLocation } from "react-router-dom";
import { Toaster } from "sonner";
import { ThemeProvider, useTheme } from "@/providers/theme-provider";
import { BrandingProvider } from "@/providers/branding-provider";
import { MessagesProvider, useMessages } from "@/providers/messages-provider";
import { AuthProvider, useAuth } from "@/providers/auth-provider";
import { LoginView } from "@/views/login";
import { PortfolioPage } from "@/views/portfolio";
import { DivKitView } from "@/views/divkit-view";
import { WidgetPortals } from "@/lib/widget-bridge";
import { FormPortals } from "@/lib/form-bridge";
import { ListPortals } from "@/lib/list-bridge";
import { IconPortals } from "@/lib/icon-bridge";
import { HintPortals } from "@/lib/hint-bridge";
import { ActionsMenuPortals } from "@/lib/actions-menu-bridge";
import { ConstantsPortals } from "@/lib/constants-bridge";
import { ActionsBarPortals } from "@/lib/actions-bar-bridge";
import { CommentsPortals } from "@/lib/comments-bridge";
import { NavPresencePortals } from "@/lib/nav-presence-bridge";
import { GeoPortals } from "@/lib/geo-bridge";
import { UpdateNotice } from "@/components/update-notice";
import { BASE_PATH } from "@/lib/base-path";

function ProtectedApp() {
  const { user, loading } = useAuth();
  const location = useLocation();
  const t = useMessages();

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

  // The entire authenticated app is server-driven DivKit (chrome + content).
  // WidgetPortals lives alongside it so DivKit's div-custom blocks (charts,
  // calendars, kanban) render as React widgets within the app's providers.
  return (
    <>
      <UpdateNotice />
      <DivKitView />
      <WidgetPortals />
      <FormPortals />
      <ListPortals />
      <IconPortals />
      <HintPortals />
      <ActionsMenuPortals />
      <ConstantsPortals />
      <ActionsBarPortals />
      <CommentsPortals />
      <NavPresencePortals />
      <GeoPortals />
    </>
  );
}

// Sonner follows the app theme (light/dark/system) so toasts match the current mode. Lives inside
// ThemeProvider so it can read the theme; "system" lets Sonner track the OS preference itself.
function ThemedToaster() {
  const { theme } = useTheme();
  return <Toaster theme={theme} richColors position="bottom-right" />;
}

function WorkspaceProviders({ children }: { children: ReactNode }) {
  return (
    <ThemeProvider>
      <BrandingProvider>
        <MessagesProvider>
          <AuthProvider>
            {children}
            <ThemedToaster />
          </AuthProvider>
        </MessagesProvider>
      </BrandingProvider>
    </ThemeProvider>
  );
}

export default function App() {
  return (
    <BrowserRouter basename={BASE_PATH}>
      <Routes>
        <Route path="/portfolio" element={<PortfolioPage />} />
        <Route path="/login" element={<WorkspaceProviders><LoginView /></WorkspaceProviders>} />
        <Route path="*" element={<WorkspaceProviders><ProtectedApp /></WorkspaceProviders>} />
      </Routes>
    </BrowserRouter>
  );
}
