import { useEffect, useRef, useState } from "react";
import { Segmented } from "@/components/ui/segmented";
import { EntityListWidget, type ListDescriptor } from "@/components/entity-list-widget";

/**
 * A register surface: one or more named views over the same register, each its own paginated
 * {@link EntityListWidget}. A BALANCE register has two views (Balance + Movements); a TURNOVER
 * register has just Movements. The view picker is a plain React segmented toggle rendered inside
 * the list's own control island (via {@code headerExtra}), right beside the title — so the switch
 * reads as one of the list's controls instead of floating above the card. With a single view the
 * toggle is omitted entirely.
 *
 * <p>Once activated, a view stays mounted and is merely hidden while the other tab is up: switching
 * back is instant (no skeleton, no count flash, no refetch) and keeps the view's own filters, sort
 * and scroll position — remounting per switch made the whole header reflow while the fresh list
 * re-counted. Views the user never opened are not mounted (no wasted fetch). The synthetic resize
 * on switch makes the just-revealed list re-measure its height (it measures 0-rects while hidden).
 */

export type RegisterView = { key: string; label: string; list: ListDescriptor };
export type RegisterDescriptor = { views: RegisterView[] };

export function RegisterSurface({ register }: { register: RegisterDescriptor }) {
  const views = register?.views ?? [];
  const [active, setActive] = useState<string>(views[0]?.key ?? "");
  const visited = useRef<Set<string>>(new Set(active ? [active] : []));
  const view = views.find((v) => v.key === active) ?? views[0];

  useEffect(() => {
    window.dispatchEvent(new Event("resize"));
  }, [active]);

  if (!view) return null;
  const switchTo = (key: string) => {
    visited.current.add(key);
    setActive(key);
  };
  const toggle =
    views.length > 1 ? (
      <Segmented
        value={view.key}
        options={views.map((v) => ({ value: v.key, label: v.label }))}
        onChange={switchTo}
      />
    ) : undefined;
  return (
    <>
      {views
        .filter((v) => v.key === view.key || visited.current.has(v.key))
        .map((v) => (
          <div key={v.key} className={v.key === view.key ? undefined : "hidden"}>
            <EntityListWidget list={v.list} headerExtra={toggle} />
          </div>
        ))}
    </>
  );
}
