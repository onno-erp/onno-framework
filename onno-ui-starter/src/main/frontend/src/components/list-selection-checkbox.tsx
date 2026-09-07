import { Checkbox } from "@/components/ui/checkbox";

/** Keep selection controls independent from the row's open/range/context-menu gestures. */
export function ListSelectionCheckbox({ checked, label, disabled, onChange }: {
  checked: boolean | "indeterminate";
  label: string;
  disabled?: boolean;
  onChange: (checked: boolean) => void;
}) {
  return (
    <span className="flex items-center justify-center" onClick={e => e.stopPropagation()}
      onMouseDown={e => e.stopPropagation()} onContextMenu={e => e.stopPropagation()}>
      <Checkbox checked={checked} disabled={disabled} aria-label={label}
        className="relative cursor-pointer after:absolute after:-inset-2.5 after:content-[''] data-[state=unchecked]:border-muted-foreground/60 data-[state=unchecked]:bg-background dark:data-[state=unchecked]:bg-background hover:data-[state=unchecked]:border-primary"
        onCheckedChange={value => onChange(value === true)} />
    </span>
  );
}

export function loadedSelectionState(ids: string[], selected: Set<string>): boolean | "indeterminate" {
  const count = ids.filter(id => selected.has(id)).length;
  return count === 0 ? false : count === ids.length ? true : "indeterminate";
}

export function selectLoaded(prev: Set<string>, ids: string[], checked: boolean): Set<string> {
  const next = new Set(prev);
  ids.forEach(id => checked ? next.add(id) : next.delete(id));
  return next;
}
