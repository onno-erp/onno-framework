import { EntityList } from "@onno/ui";

type Props = {
  /** Only open orders, grouped by status. */
  open?: boolean;
  fill?: boolean;
  selection?: string[];
  onSelectionChange?: (ids: string[]) => void;
};

/**
 * The Orders list as a page block. Columns, filters and row tint come from entities/orders.ts.
 * Pass `selection`/`onSelectionChange` to let the page react to what the user picks.
 */
export function OrdersBoard({ open, fill, selection, onSelectionChange }: Props) {
  return (
    <EntityList
      entity="Orders"
      fill={fill}
      where={open ? { open: true } : undefined}
      defaultGroupBy={open ? "status" : undefined}
      selection={selection}
      onSelectionChange={onSelectionChange}
    />
  );
}
