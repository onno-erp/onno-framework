import { Button, openRecord, useModel } from "@onno/ui";
import { Plus } from "@onno/ui/icons";

/** An ordinary React action, usable in a navigation header or on a page. */
export function NewOrderButton() {
  const canCreate = useModel()?.entities.find(
    (entity) => entity.logicalName === "Orders",
  )?.canWrite;
  if (!canCreate) return null;
  return (
    <Button
      variant="ghost"
      size="icon"
      aria-label="New order"
      className="rounded-xl h-8 w-8"
      onClick={() => openRecord("Orders")}
    >
      <Plus className="size-2" />
    </Button>
  );
}
