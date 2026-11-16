const SYSTEM_KEYS: Record<string, string> = {
  _id: "id",
  _code: "code",
  _description: "description",
  _deletion_mark: "deletionMark",
  _is_folder: "folder",
  _parent: "parent",
  _version: "version",
  _number: "number",
  _date: "date",
  _posted: "posted",
  _actions: "actions",
  _style: "style",
  _parent_id: "parentId",
  _line_number: "lineNumber",
  // Registers intentionally retain their storage-shaped projection.
  _period: "_period",
  _active: "_active",
  _movement_type: "_movement_type",
};

/** Compatibility helper for authored storage identifiers; resolved metadata already supplies logical keys. */
export function logicalEntityKey(key: string): string {
  if (!key) return key;
  if (SYSTEM_KEYS[key]) return SYSTEM_KEYS[key];
  const sidecar = key.match(/^(.*)_(display|ref|code|avatar|color)$/);
  if (sidecar) {
    const base = logicalEntityKey(sidecar[1]);
    return base + sidecar[2].charAt(0).toUpperCase() + sidecar[2].slice(1);
  }
  return key.replace(/_([a-z0-9])/g, (_, c: string) => c.toUpperCase());
}
