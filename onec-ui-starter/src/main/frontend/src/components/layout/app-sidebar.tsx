import { useEffect, useState } from "react";
import { Link, useLocation } from "react-router-dom";
import {
  BookOpen,
  FileText,
  BarChart3,
  FolderOpen,
  ChevronDown,
  ChevronRight,
  Home,
  Euro,
  DollarSign,
  Users,
  Book,
  Building2,
  Briefcase,
  Settings,
  Package,
  ShoppingCart,
  Calendar,
  Mail,
  Tag,
} from "lucide-react";
import { api } from "@/lib/api";
import { cn } from "@/lib/utils";
import { AccountMenu } from "./account-menu";
import type { LayoutSection } from "@/lib/types";

const iconMap: Record<string, React.ElementType> = {
  "book-open": BookOpen,
  "file-text": FileText,
  "bar-chart": BarChart3,
  "folder": FolderOpen,
  "home": Home,
  "euro": Euro,
  "dollar": DollarSign,
  "users": Users,
  "book": Book,
  "building": Building2,
  "briefcase": Briefcase,
  "settings": Settings,
  "package": Package,
  "cart": ShoppingCart,
  "calendar": Calendar,
  "mail": Mail,
  "tag": Tag,
};

function resolveIcon(icon: string, sectionName: string): React.ElementType {
  if (icon && iconMap[icon]) return iconMap[icon];
  const fallback: Record<string, React.ElementType> = {
    Catalogs: BookOpen,
    Documents: FileText,
    Registers: BarChart3,
  };
  return fallback[sectionName] ?? FolderOpen;
}

interface AppSidebarProps {
  onNavigate?: () => void;
}

export function AppSidebar({ onNavigate }: AppSidebarProps) {
  const [sections, setSections] = useState<LayoutSection[]>([]);
  const [collapsed, setCollapsed] = useState<Record<string, boolean>>({});
  const location = useLocation();
  const rendererQuery = new URLSearchParams(location.search).get("renderer") === "divkit"
    ? "?renderer=divkit"
    : "";

  useEffect(() => {
    api.getLayout().then((layout) => {
      setSections(layout.filter((s) => s.placement === "sidebar"));
    });
  }, []);

  const toggle = (title: string) =>
    setCollapsed((prev) => ({ ...prev, [title]: !prev[title] }));

  return (
    <aside className="flex h-full w-56 flex-col border-r border-border bg-background text-foreground">
      <div className="flex h-12 items-center px-4">
        <span className="text-sm font-semibold tracking-tight">OneC</span>
      </div>

      <nav className="flex-1 overflow-y-auto px-2 pb-2">
        <Link
          to={`/${rendererQuery}`}
          onClick={onNavigate}
          className={cn(
            "flex items-center gap-2 rounded-md px-2.5 py-1.5 text-[13px] transition-colors",
            location.pathname === "/"
              ? "bg-accent text-accent-foreground font-medium"
              : "text-muted-foreground hover:text-foreground hover:bg-accent"
          )}
        >
          <Home className="h-3.5 w-3.5" />
          Home
        </Link>

        <div className="mt-4 space-y-3">
          {sections.map((section) => {
            const Icon = resolveIcon(section.icon, section.name);
            return (
              <div key={section.name}>
                <button
                  onClick={() => toggle(section.name)}
                  className="flex w-full items-center gap-1.5 px-2.5 py-1 text-[11px] font-medium uppercase tracking-wider text-muted-foreground/60 hover:text-muted-foreground transition-colors"
                >
                  <Icon className="h-3.5 w-3.5" />
                  {section.name}
                  {collapsed[section.name] ? (
                    <ChevronRight className="ml-auto h-3 w-3" />
                  ) : (
                    <ChevronDown className="ml-auto h-3 w-3" />
                  )}
                </button>

                {!collapsed[section.name] && (
                  <div className="mt-0.5 space-y-px">
                    {section.items.map((item) => (
                      <Link
                        key={item.href}
                        to={`${item.href}${rendererQuery}`}
                        onClick={onNavigate}
                        className={cn(
                          "flex items-center rounded-md px-2.5 py-1.5 text-[13px] transition-colors",
                          location.pathname === item.href
                            ? "bg-accent text-accent-foreground font-medium"
                            : "text-muted-foreground hover:text-foreground hover:bg-accent"
                        )}
                      >
                        {item.name}
                      </Link>
                    ))}
                  </div>
                )}
              </div>
            );
          })}
        </div>
      </nav>

      <div className="border-t border-border px-2 py-2">
        <AccountMenu onAfterSignOut={onNavigate} />
      </div>
    </aside>
  );
}
