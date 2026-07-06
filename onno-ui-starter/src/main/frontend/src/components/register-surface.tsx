import { useState } from "react";
import { cn } from "@/lib/utils";
import { EntityListWidget, type ListDescriptor } from "@/components/entity-list-widget";

/**
 * A register surface: one or more named views over the same register, each its own paginated
 * {@link EntityListWidget}. A BALANCE register has two views (Balance + Movements); a TURNOVER
 * register has just Movements. The view picker is a plain React segmented toggle (the same control
 * as the list's Table/Map switch) — React owns which list is mounted, so switching can never blank
 * the surface (the old DivKit tabs unmounted the portaled island on switch), and there's no
 * animation. With a single view the toggle is omitted entirely.
 */

export type RegisterView = { key: string; label: string; list: ListDescriptor };
export type RegisterDescriptor = { views: RegisterView[] };

export function RegisterSurface({ register }: { register: RegisterDescriptor }) {
  const views = register?.views ?? [];
  const [active, setActive] = useState<string>(views[0]?.key ?? "");
  const view = views.find((v) => v.key === active) ?? views[0];
  if (!view) return null;
  return (
    <div className="pointer-events-auto flex flex-col">
      {views.length > 1 ? (
        <div className="px-4 pt-4 sm:px-6">
          <div className="inline-flex h-9 items-center rounded-control border border-input bg-muted p-0.5">
            {views.map((v) => (
              <button
                key={v.key}
                type="button"
                onClick={() => setActive(v.key)}
                aria-pressed={v.key === view.key}
                className={cn(
                  "inline-flex h-8 items-center rounded-control px-3 text-sm font-medium",
                  v.key === view.key
                    ? "bg-card text-foreground shadow-sm"
                    : "text-muted-foreground hover:text-foreground"
                )}
              >
                {v.label}
              </button>
            ))}
          </div>
        </div>
      ) : null}
      {/* Remount per view (fresh state + fetch) when switching. The widget carries its own gutter. */}
      <EntityListWidget key={view.key} list={view.list} />
    </div>
  );
}
