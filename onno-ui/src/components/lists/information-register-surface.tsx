import { useState } from "react";
import { useCustomizedList } from "../../lib/app-config";
import { useMessages } from "../../providers/messages-provider";
import { Input } from "../ui/input";
import { Segmented } from "../ui/segmented";
import { EntityListView, type ListDescriptor } from "./entity-list";

export type InformationRegisterDescriptor = { periodic: boolean; list: ListDescriptor };

/** A read-only register list. Periodic registers also offer a paged slice at a chosen date. */
export function InformationRegisterSurface({ register }: { register: InformationRegisterDescriptor }) {
  const t = useMessages();
  const [mode, setMode] = useState("records");
  const [at, setAt] = useState(() => {
    const now = new Date();
    return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, "0")}-${String(now.getDate()).padStart(2, "0")}`;
  });
  const customized = useCustomizedList(register.list)!;
  const latest = register.periodic && mode === "latest";
  // In a slice, filtering the period would hide the last value in force. Resource/dimension
  // filters still apply to the selected values, after the latest record is selected.
  const list = latest ? { ...customized, filters: customized.filters?.filter(f => f.column !== "_period") } : customized;
  const controls = register.periodic ? (
    <div className="flex flex-wrap items-center gap-2">
      <Segmented value={mode} onChange={setMode} options={[
        { value: "records", label: t("register.records") },
        { value: "latest", label: t("register.latest") },
      ]} />
      {mode === "latest" ? <label className="flex items-center gap-2 text-sm">
        {t("register.date")}
        <Input type="date" aria-label={t("register.date")} value={at} onChange={e => { if (e.target.value) setAt(e.target.value); }} className="w-40" />
      </label> : null}
    </div>
  ) : undefined;
  // PostgreSQL stores microseconds; extra nanoseconds round up into the following day.
  return <EntityListView key={register.list.name} list={list} headerExtra={controls}
    queryParams={latest ? { at: [`${at}T23:59:59.999999`] } : undefined} />;
}
