import { Plus, Trash2 } from "lucide-react";
import type { TabularSectionMeta, AttributeMeta, EntityRecord } from "@/lib/types";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Checkbox } from "@/components/ui/checkbox";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { RefSelect } from "@/components/ref-select";
import { DatePicker } from "@/components/date-picker";
import {
  Table,
  TableHeader,
  TableBody,
  TableRow,
  TableHead,
  TableCell,
} from "@/components/ui/table";

interface TabularSectionEditorProps {
  section: TabularSectionMeta;
  rows: EntityRecord[];
  onChange: (rows: EntityRecord[]) => void;
}

function fieldType(attr: AttributeMeta): string {
  if (attr.isRef) return "text";
  switch (attr.javaType) {
    case "BigDecimal":
    case "int":
    case "Integer":
    case "long":
    case "Long":
    case "double":
    case "Double":
      return "number";
    case "boolean":
    case "Boolean":
      return "checkbox";
    case "LocalDate":
      return "date";
    case "LocalDateTime":
      return "datetime-local";
    default:
      return "text";
  }
}

export function TabularSectionEditor({ section, rows, onChange }: TabularSectionEditorProps) {
  const visibleAttrs = section.attributes.filter((a) => a.visibleInForm !== false);
  const updateRow = (index: number, key: string, value: unknown) => {
    const updated = rows.map((row, i) =>
      i === index ? { ...row, [key]: value } : row
    );
    onChange(updated);
  };

  const addRow = () => {
    onChange([...rows, {}]);
  };

  const removeRow = (index: number) => {
    onChange(rows.filter((_, i) => i !== index));
  };

  const renderCell = (attr: AttributeMeta, row: EntityRecord, rowIndex: number) => {
    if (attr.isEnum && attr.enumValues) {
      return (
        <Select
          value={(row[attr.fieldName] as string) ?? ""}
          onValueChange={(v) => updateRow(rowIndex, attr.fieldName, v)}
        >
          <SelectTrigger className="min-w-[120px]">
            <SelectValue placeholder={`Select`} />
          </SelectTrigger>
          <SelectContent>
            {attr.enumValues.map((ev) => (
              <SelectItem key={ev.id} value={ev.id}>
                {ev.name}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      );
    }

    if (attr.isRef && attr.refTarget) {
      return (
        <RefSelect
          catalogName={attr.refTarget}
          value={row[attr.fieldName] as string}
          onChange={(id) => updateRow(rowIndex, attr.fieldName, id)}
        />
      );
    }

    const type = fieldType(attr);

    if (type === "checkbox") {
      return (
        <Checkbox
          checked={!!row[attr.fieldName]}
          onCheckedChange={(v) => updateRow(rowIndex, attr.fieldName, v)}
        />
      );
    }

    if (type === "date" || type === "datetime-local") {
      return (
        <DatePicker
          value={(row[attr.fieldName] as string) ?? ""}
          onChange={(val) => updateRow(rowIndex, attr.fieldName, val)}
          includeTime={type === "datetime-local"}
        />
      );
    }

    return (
      <Input
        type={type}
        step={type === "number" && attr.scale > 0 ? Math.pow(10, -attr.scale).toString() : undefined}
        value={(row[attr.fieldName] as string) ?? ""}
        onChange={(e) =>
          updateRow(
            rowIndex,
            attr.fieldName,
            type === "number" ? parseFloat(e.target.value) || "" : e.target.value
          )
        }
        className="min-w-[100px]"
      />
    );
  };

  return (
    <div className="space-y-3">
      <div className="rounded-lg border overflow-x-auto">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead className="w-[50px]">#</TableHead>
              {visibleAttrs.map((a) => (
                <TableHead key={a.fieldName}>{a.displayName}</TableHead>
              ))}
              <TableHead className="w-[50px]" />
            </TableRow>
          </TableHeader>
          <TableBody>
            {rows.length === 0 && (
              <TableRow>
                <TableCell
                  colSpan={2 + visibleAttrs.length}
                  className="text-center text-muted-foreground py-6"
                >
                  No rows. Click "Add Row" to start.
                </TableCell>
              </TableRow>
            )}
            {rows.map((row, i) => (
              <TableRow key={i}>
                <TableCell className="text-muted-foreground">{i + 1}</TableCell>
                {visibleAttrs.map((attr) => (
                  <TableCell key={attr.fieldName}>
                    {renderCell(attr, row, i)}
                  </TableCell>
                ))}
                <TableCell>
                  <Button
                    type="button"
                    size="icon"
                    variant="ghost"
                    onClick={() => removeRow(i)}
                  >
                    <Trash2 className="h-4 w-4" />
                  </Button>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>
      <Button type="button" variant="outline" size="sm" onClick={addRow}>
        <Plus className="h-4 w-4 mr-2" />
        Add Row
      </Button>
    </div>
  );
}
