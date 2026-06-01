import { Avatar, AvatarImage, AvatarFallback } from "@/components/ui/avatar";
import { cn } from "@/lib/utils";

interface RefDisplayProps {
  /**
   * Display string (typically from `<col>_display`).
   */
  display?: string;
  /**
   * Avatar URL when the referenced record has one (typically from `<col>_avatar`).
   */
  avatarUrl?: string;
  /**
   * Optional record code (e.g. "E-000001") shown as a tooltip on hover.
   */
  code?: string;
  size?: "xs" | "sm" | "md";
  /** When true, only the avatar is rendered (label tooltip). */
  iconOnly?: boolean;
  className?: string;
}

const sizeMap: Record<NonNullable<RefDisplayProps["size"]>, string> = {
  xs: "h-5 w-5 text-[9px]",
  sm: "h-6 w-6 text-[10px]",
  md: "h-7 w-7 text-[11px]",
};

function initials(name: string | undefined): string {
  if (!name) return "?";
  const parts = name.trim().split(/[\s._-]+/).filter(Boolean);
  if (parts.length >= 2) return (parts[0][0] + parts[1][0]).toUpperCase();
  return (name.trim().slice(0, 2) || "?").toUpperCase();
}

/**
 * Render a referenced record. Renders an avatar only when one exists on the
 * referenced entity. Code (e.g. `E-000001`) shows up as a hover tooltip.
 */
export function RefDisplay({
  display,
  avatarUrl,
  code,
  size = "sm",
  iconOnly = false,
  className,
}: RefDisplayProps) {
  if (!display && !avatarUrl && !code) return null;
  const tooltip = code ? `${display ?? ""}${display ? " · " : ""}${code}` : display;

  if (iconOnly && avatarUrl) {
    return (
      <span className={cn("inline-flex", className)} title={tooltip}>
        <Avatar className={sizeMap[size]}>
          <AvatarImage src={avatarUrl} alt={display ?? ""} />
          <AvatarFallback>{initials(display)}</AvatarFallback>
        </Avatar>
      </span>
    );
  }

  return (
    <span
      className={cn("inline-flex items-center gap-1.5 align-middle", className)}
      title={tooltip}
    >
      {avatarUrl && (
        <Avatar className={sizeMap[size]}>
          <AvatarImage src={avatarUrl} alt={display ?? ""} />
          <AvatarFallback>{initials(display)}</AvatarFallback>
        </Avatar>
      )}
      {display && <span className="truncate">{display}</span>}
    </span>
  );
}

interface RowRefDisplayProps {
  row: Record<string, unknown>;
  columnName: string;
  size?: "xs" | "sm" | "md";
  iconOnly?: boolean;
  className?: string;
}

/**
 * Convenience wrapper that reads `<col>_display` / `<col>_avatar` / `<col>_code`
 * from a row.
 */
export function RowRefDisplay({
  row,
  columnName,
  size,
  iconOnly,
  className,
}: RowRefDisplayProps) {
  const display = row[`${columnName}_display`] as string | undefined;
  const avatarUrl = row[`${columnName}_avatar`] as string | undefined;
  const code = row[`${columnName}_code`] as string | undefined;
  if (!display && !avatarUrl && !code) return null;
  return (
    <RefDisplay
      display={display}
      avatarUrl={avatarUrl}
      code={code}
      size={size}
      iconOnly={iconOnly}
      className={className}
    />
  );
}
