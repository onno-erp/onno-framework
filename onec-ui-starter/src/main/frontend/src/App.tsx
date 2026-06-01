import type { ReactNode } from "react";
import { BrowserRouter, Navigate, Routes, Route, useLocation, useSearchParams } from "react-router-dom";
import { Toaster } from "sonner";
import { ThemeProvider } from "@/providers/theme-provider";
import { WidgetRegistryProvider } from "@/providers/widget-registry";
import { AuthProvider, useAuth } from "@/providers/auth-provider";
import { AppShell } from "@/components/layout/app-shell";
import { HomePage } from "@/views/home";
import { LoginView } from "@/views/login";
import { CatalogListView } from "@/views/catalog-list";
import { DocumentListView } from "@/views/document-list";
import { DocumentDetailView } from "@/views/document-detail";
import { RegisterReportView } from "@/views/register-report";
import { PortfolioPage } from "@/views/portfolio";
import { builtInDashboardWidgets } from "@/views/home";
import { DivKitView } from "@/views/divkit-view";

function ProtectedApp() {
  const { user, loading } = useAuth();
  const location = useLocation();

  if (loading) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-background text-sm text-muted-foreground">
        Loading workspace...
      </div>
    );
  }

  if (!user) {
    return <Navigate to="/login" replace state={{ from: location }} />;
  }

  return <AppShell />;
}

function WorkspaceProviders({ children }: { children: ReactNode }) {
  return (
    <ThemeProvider>
      <WidgetRegistryProvider builtInDashboardWidgets={builtInDashboardWidgets}>
        <AuthProvider>
          {children}
          <Toaster richColors position="bottom-right" />
        </AuthProvider>
      </WidgetRegistryProvider>
    </ThemeProvider>
  );
}

function RendererSwitch({ react, divkit }: { react: ReactNode; divkit: ReactNode }) {
  const [params] = useSearchParams();
  return params.get("renderer") === "divkit" ? divkit : react;
}

export default function App() {
  return (
    <BrowserRouter basename="/ui">
      <Routes>
        <Route path="portfolio" element={<PortfolioPage />} />
        <Route path="login" element={<WorkspaceProviders><LoginView /></WorkspaceProviders>} />
        <Route element={<WorkspaceProviders><ProtectedApp /></WorkspaceProviders>}>
          <Route index element={<RendererSwitch react={<HomePage />} divkit={<DivKitView />} />} />
          <Route path="catalogs/:name" element={<RendererSwitch react={<CatalogListView />} divkit={<DivKitView />} />} />
          <Route path="documents/:name" element={<RendererSwitch react={<DocumentListView />} divkit={<DivKitView />} />} />
          <Route path="documents/:name/:id" element={<RendererSwitch react={<DocumentDetailView />} divkit={<DivKitView />} />} />
          <Route path="registers/:name" element={<RendererSwitch react={<RegisterReportView />} divkit={<DivKitView />} />} />
        </Route>
      </Routes>
    </BrowserRouter>
  );
}
