import { cn } from "@/lib/utils";

/**
 * An accessible on/off toggle (a settings-style switch). Dependency-free: a single button with
 * {@code role="switch"} + {@code aria-checked}, keyboard-operable, styled to match the shadcn look.
 * Used for boolean fields hinted with {@code .widget("switch")} and on the Settings page.
 */
export function Switch({
  checked = false,
  onCheckedChange,
  disabled,
  id,
  className,
}: {
  checked?: boolean;
  onCheckedChange?: (checked: boolean) => void;
  disabled?: boolean;
  id?: string;
  className?: string;
}) {
  return (
    <button
      type="button"
      role="switch"
      id={id}
      aria-checked={checked}
      disabled={disabled}
      onClick={() => onCheckedChange?.(!checked)}
      className={cn(
        "peer inline-flex h-6 w-11 shrink-0 cursor-pointer items-center rounded-full border-2 border-transparent transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background disabled:cursor-not-allowed disabled:opacity-50",
        checked ? "bg-primary" : "bg-input",
        className
      )}
    >
      <span
        className={cn(
          "pointer-events-none block size-5 rounded-full bg-background shadow-sm ring-0 transition-transform",
          checked ? "translate-x-5" : "translate-x-0"
        )}
      />
    </button>
  );
}
