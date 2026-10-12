import { Lock } from "lucide-react";

import { useMessages } from "@/providers/messages-provider";
import { cn } from "@/lib/utils";

/**
 * A reference to a record the viewer may not read (outside their record scope, or an entity they
 * have no read access to). The server withholds the target's id and display name and marks the ref
 * `{field}Restricted`; the UI shows this neutral chip instead of a link or picker.
 */
export function RestrictedRef({ className }: { className?: string }) {
  const t = useMessages();
  return (
    <span
      className={cn(
        "inline-flex max-w-full items-center gap-1.5 rounded-control border border-border bg-muted px-2 py-0.5 text-xs text-muted-foreground",
        className
      )}
      title={t("ref.restrictedHint")}
    >
      <Lock className="size-3 shrink-0" aria-hidden />
      <span className="truncate">{t("ref.restricted")}</span>
    </span>
  );
}

/** Whether a logical row marks `field` as a restricted ref. */
export function isRestrictedRef(row: Record<string, unknown> | null | undefined, field: string): boolean {
  return row?.[`${field}Restricted`] === true || row?.[`${field}_restricted`] === true;
}
