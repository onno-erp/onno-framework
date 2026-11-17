import { HexColorPicker } from "react-colorful";
import { Input } from "../ui/input";
import { Popover, PopoverContent, PopoverTrigger } from "../ui/popover";
import { cn } from "../../lib/utils";
import { useMessages } from "../../providers/messages-provider";

const HEX_COLOR = /^#[0-9a-f]{6}$/i;

export function normalizeHexColor(value: string): string {
  const trimmed = value.trim();
  const digits = trimmed.startsWith("#") ? trimmed.slice(1) : trimmed;
  if (/^[0-9a-f]{3}$/i.test(digits)) {
    return `#${digits
      .split("")
      .map((digit) => digit + digit)
      .join("")
      .toLowerCase()}`;
  }
  return /^[0-9a-f]{6}$/i.test(digits) ? `#${digits.toLowerCase()}` : trimmed;
}

export function ColorPicker({
  value = "",
  onChange,
  onCommit,
  invalid,
  placeholder,
  compact = false,
  disabled = false,
  "aria-label": ariaLabel,
}: {
  value?: string;
  onChange: (value: string) => void;
  /** Called once with the final colour when the picker closes (or the hex field is left), not on every drag step. */
  onCommit?: (value: string) => void;
  invalid?: boolean;
  placeholder?: string;
  /** Swatch-only trigger for dense rows; the hex field moves into the popover. */
  compact?: boolean;
  disabled?: boolean;
  "aria-label"?: string;
}) {
  const t = useMessages();
  const valid = HEX_COLOR.test(value);
  const hexField = (className?: string) => (
    <Input
      aria-label={t("form.hexColor")}
      aria-invalid={invalid}
      className={cn(invalid && "border-destructive focus-visible:ring-destructive", className)}
      autoComplete="off"
      inputMode="text"
      maxLength={7}
      placeholder={placeholder || "#RRGGBB"}
      spellCheck={false}
      disabled={disabled}
      value={value}
      onChange={(event) => onChange(event.target.value)}
      onBlur={(event) => {
        const normalized = normalizeHexColor(event.currentTarget.value);
        if (normalized !== value) onChange(normalized);
        if (!compact && HEX_COLOR.test(normalized)) onCommit?.(normalized);
      }}
    />
  );
  if (compact) {
    return (
      <Popover onOpenChange={(open) => { if (!open && valid) onCommit?.(value); }}>
        <PopoverTrigger asChild>
          <button
            type="button"
            disabled={disabled}
            aria-label={ariaLabel || t("form.chooseColor")}
            title={ariaLabel || t("form.chooseColor")}
            className="size-7 shrink-0 rounded-full border border-border/60 transition-shadow hover:ring-2 hover:ring-ring/40 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-50"
            style={valid ? { backgroundColor: value } : undefined}
          />
        </PopoverTrigger>
        <PopoverContent className="onno-color-picker w-auto space-y-2 p-3" align="start">
          <HexColorPicker color={valid ? value : "#000000"} onChange={onChange} />
          {hexField("h-8 text-center")}
        </PopoverContent>
      </Popover>
    );
  }

  return (
    <div className="flex items-center gap-2">
      {hexField()}
      <Popover onOpenChange={(open) => { if (!open && valid) onCommit?.(value); }}>
        <PopoverTrigger asChild>
          <button
            type="button"
            disabled={disabled}
            aria-label={t("form.chooseColor")}
            title={t("form.chooseColor")}
            className="grid size-10 shrink-0 place-items-center rounded-field border border-input bg-muted transition-colors hover:bg-accent focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-50"
          >
            <span
              aria-hidden="true"
              className={cn("size-6 rounded-field border border-border", !valid && "bg-background")}
              style={valid ? { backgroundColor: value } : undefined}
            />
          </button>
        </PopoverTrigger>
        <PopoverContent className="onno-color-picker p-3" align="end">
          <HexColorPicker color={valid ? value : "#000000"} onChange={onChange} />
        </PopoverContent>
      </Popover>
    </div>
  );
}
