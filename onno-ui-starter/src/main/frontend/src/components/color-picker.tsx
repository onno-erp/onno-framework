import { HexColorPicker } from "react-colorful";
import { Input } from "@/components/ui/input";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { cn } from "@/lib/utils";
import { useMessages } from "@/providers/messages-provider";

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
  invalid,
  placeholder,
}: {
  value?: string;
  onChange: (value: string) => void;
  invalid?: boolean;
  placeholder?: string;
}) {
  const t = useMessages();
  const valid = HEX_COLOR.test(value);

  return (
    <div className="flex items-center gap-2">
      <Input
        aria-label={t("form.hexColor")}
        aria-invalid={invalid}
        className={cn(invalid && "border-destructive focus-visible:ring-destructive")}
        autoComplete="off"
        inputMode="text"
        maxLength={7}
        placeholder={placeholder || "#RRGGBB"}
        spellCheck={false}
        value={value}
        onChange={(event) => onChange(event.target.value)}
        onBlur={(event) => {
          const normalized = normalizeHexColor(event.currentTarget.value);
          if (normalized !== value) onChange(normalized);
        }}
      />
      <Popover>
        <PopoverTrigger asChild>
          <button
            type="button"
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
