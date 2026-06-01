import { useEffect, useMemo, useState } from "react";
import { api } from "@/lib/api";
import { toSnakeCase } from "@/lib/utils";
import type { EntityRecord } from "@/lib/types";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Avatar, AvatarImage, AvatarFallback } from "@/components/ui/avatar";

interface RefSelectProps {
  catalogName: string;
  value?: string;
  onChange: (id: string) => void;
}

function initials(name: string | undefined): string {
  if (!name) return "?";
  const parts = name.trim().split(/[\s._-]+/).filter(Boolean);
  if (parts.length >= 2) return (parts[0][0] + parts[1][0]).toUpperCase();
  return (name.trim().slice(0, 2) || "?").toUpperCase();
}

function displayOf(item: EntityRecord): string {
  const desc = item._description as string | undefined;
  if (desc && desc.trim()) return desc;
  return (item._code as string) ?? (item._id as string) ?? "";
}

export function RefSelect({ catalogName, value, onChange }: RefSelectProps) {
  const [items, setItems] = useState<EntityRecord[]>([]);

  useEffect(() => {
    api.listCatalog(toSnakeCase(catalogName)).then(setItems);
  }, [catalogName]);

  const byId = useMemo(() => {
    const map = new Map<string, EntityRecord>();
    for (const item of items) map.set(item._id as string, item);
    return map;
  }, [items]);

  const selected = value ? byId.get(value) : undefined;

  return (
    <Select value={value ?? ""} onValueChange={onChange}>
      <SelectTrigger>
        <SelectValue placeholder={`Select ${catalogName}...`}>
          {selected ? (
            <RefRow item={selected} />
          ) : (
            <span className="text-muted-foreground">Select {catalogName}...</span>
          )}
        </SelectValue>
      </SelectTrigger>
      <SelectContent>
        {items.map((item) => (
          <SelectItem key={item._id as string} value={item._id as string}>
            <RefRow item={item} />
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
  );
}

function RefRow({ item }: { item: EntityRecord }) {
  const display = displayOf(item);
  const avatarUrl = (item.avatar_url as string | undefined) ?? undefined;
  const code = item._code as string | undefined;
  return (
    <span className="inline-flex items-center gap-2" title={code ? `${display} · ${code}` : display}>
      {avatarUrl ? (
        <Avatar className="h-5 w-5 text-[9px]">
          <AvatarImage src={avatarUrl} alt={display} />
          <AvatarFallback>{initials(display)}</AvatarFallback>
        </Avatar>
      ) : null}
      <span className="truncate">{display}</span>
    </span>
  );
}
