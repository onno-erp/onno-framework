import { useState } from "react";
import { Outlet } from "react-router-dom";
import { Menu, X } from "lucide-react";
import { AppSidebar } from "./app-sidebar";
import { Button } from "@/components/ui/button";

export function AppShell() {
  const [mobileNavOpen, setMobileNavOpen] = useState(false);

  return (
    <div className="flex h-screen overflow-hidden bg-background">
      <div className="hidden md:block">
        <AppSidebar />
      </div>

      {mobileNavOpen && (
        <div className="fixed inset-0 z-50 md:hidden">
          <button
            aria-label="Close navigation"
            className="absolute inset-0 bg-background/80 backdrop-blur-sm"
            onClick={() => setMobileNavOpen(false)}
          />
          <div className="absolute inset-y-0 left-0 w-72 max-w-[85vw] border-r border-border bg-background shadow-lg">
            <div className="absolute right-2 top-2">
              <Button
                aria-label="Close navigation"
                variant="ghost"
                size="icon"
                onClick={() => setMobileNavOpen(false)}
              >
                <X className="h-4 w-4" />
              </Button>
            </div>
            <AppSidebar onNavigate={() => setMobileNavOpen(false)} />
          </div>
        </div>
      )}

      <main className="flex min-w-0 flex-1 flex-col bg-background">
        <header className="flex h-12 items-center gap-2 border-b border-border px-3 md:hidden">
          <Button
            aria-label="Open navigation"
            variant="ghost"
            size="icon"
            onClick={() => setMobileNavOpen(true)}
          >
            <Menu className="h-5 w-5" />
          </Button>
          <span className="text-sm font-semibold">OneC</span>
        </header>
        <div className="flex-1 overflow-y-auto">
          <div className="mx-auto max-w-6xl px-4 py-4 sm:px-6 sm:py-6">
          <Outlet />
          </div>
        </div>
      </main>
    </div>
  );
}
