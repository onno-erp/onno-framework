# Replacing the default UI

The default onno components are a starting point. An app can replace a field editor, record
header or entire record screen, list body, picker, and navigation layout using public `@onno/ui`
exports. Reuse the framework's controllers and bindings so a different presentation retains the
same validation, saving, posting, dirty guard, picker constraints, and live refresh.

## Public layers

| What you want to replace | Public surface |
| --- | --- |
| One field or editor | `useRecordField(form, field)` or `<RecordField>`: typed value/setter, disabled/required flags, errors, advisory feedback, metadata, and `renderDefault()`. |
| A record screen or header | Injected `RecordPageProps<E>.form`; `useRecordForm<E>(descriptor)` for a custom host. `RecordPage`, `RecordFields`, and `RecordLines` are optional presentation blocks. |
| Record actions | `form.actions` and `RecordActions`; `useCommand` for a custom command button. |
| A list body, board, or custom workspace | `EntityList`'s `custom.component` or `EntityListView`'s `renderer`, receiving `ListRendererProps`. |
| A list cell | `ListCell` and `ListColumn`, or a field's `render` callback. |
| A reference picker | `RefSelectProps`, `RefPickerDialog`, `RefOptionSearch`, and `api.searchRefOptions`; a bound reference field supplies its resolved `reference` props. |
| Date and other controls | Exported controls plus `DatePickerProps`, `DateTimePickerProps`, and `RefSelectProps`. |
| Navigation | `Navigation`, `NavigationSection`, `NavigationGroup`, `NavigationLink`, `DefaultNavigation`, and `NavigationProps`. |
| Custom data presentation | Typed query/mutation hooks, `api`, the shared query client, and `useUiEvents`. |

Imports from `@onno/ui/src/...` are not an extension contract. Import from `@onno/ui`,
`@onno/ui/icons`, or a starter's public package. When adding a replacement boundary to the
framework, expose the shared behavior and its types deliberately, then make the default component
use that same implementation. Do not create separate save, selection, or refresh mechanisms for
the default and custom UI. This is the direction for new extension work; some existing components
still need their own controller extracted before they can be replaced at that granularity.

## Replace an editor

```tsx
import { useRecordField, type RecordPageProps } from "@onno/ui";

export function NotesEditor({ form }: RecordPageProps<"Orders">) {
  const note = useRecordField(form, "note", { label: "Internal notes" });
  return <label>
    {note.label}
    <textarea value={note.value ?? ""} disabled={note.disabled}
      aria-invalid={!!note.error}
      onChange={(event) => note.onChange(event.target.value)} />
    {note.error ? <span role="alert">{note.error}</span> : null}
    {note.feedback.map((item, index) => <span role="status" key={index}>{item.message}</span>)}
  </label>;
}
```

The same binding works with a Lexical editor, third-party input, or your own control. Its setter
updates the existing form, including cascades and the unsaved-edits guard. Field names and value
types are checked against `model.gen.ts`. A tabular section is a typed array field, so a custom
line editor can replace the section with `form.set("items", rows)` or its field binding.

`RecordField` offers the same binding as a render prop:

```tsx
<RecordField form={form} field="note">
  {(field) => <MyEditor value={field.value} onChange={field.onChange} disabled={field.disabled} />}
</RecordField>
```

Without children, it renders the standard field, including label, input and validation. A custom
editor can call `field.renderDefault()` as its fallback. `attribute` and `section` expose the
corresponding metadata. For a typed reference, `field.reference` supplies the same target,
cascading `where`, server picker `optionContext`, clear/open policy, value and setter used by the
built-in picker. Restricted references have `restricted: true`, a disabled binding, and no picker
props. A replacement must honor the supplied disabled/restricted state.

## Replace the whole record surface

A registered record page already receives the live controller. It need not render `RecordPage`:

```tsx
import { Page, RecordActions, RecordField, type RecordPageProps } from "@onno/ui";

export function OwnOrderPage({ form }: RecordPageProps<"Orders">) {
  return <Page title={form.title} actions={<RecordActions items={form.actions} />}>
    <RecordField form={form} field="customer" />
    <NotesEditor form={form} />
    <button disabled={form.readOnly || form.saving} onClick={() => void form.save()}>Save</button>
    {form.canPost ? <button disabled={form.saving} onClick={() => void form.save(true)}>Save and post</button> : null}
    <button onClick={form.cancel}>Close</button>
  </Page>;
}
// defineApp({ pages: [{ record: "Orders", component: OwnOrderPage }] });
```

The controller exposes identity, `title`, `meta` (the record schema), `values`, field `errors`, cross-field `formErrors`, advisory `feedback`, `isNew`, `readOnly`, `saving`,
`canPost`, `posted`, `dirty`, `stale`, resolved action descriptors, and save/cancel/refresh methods.
`refresh()` preserves a dirty draft and signals `stale`; `reload()` explicitly discards the draft
and adopts the stored record. Save/create/cancel retain the shell's normal record-route lifecycle.
Custom layouts should surface `stale`, form-level feedback and any custom field errors themselves;
`RecordPage` supplies that standard chrome when used.

For a custom host that already has a fetched `FormDescriptor`, call `useRecordForm<E>(descriptor)`
at the top level of that host. The default `RecordForm` uses this exact hook. Do not call it again
on an injected `form`: that would create a second draft. Mount one controller per record identity
(key a custom host by kind/name/id/duplicate when switching records); use its refresh methods for
the same record rather than replacing an in-progress draft. `useDefaultView` and `FormDescriptor` are
public for hosts that load descriptors themselves.

## Replace a list body without rebuilding its behavior

A custom `ListRendererProps` component receives the current `rows`, resolved `list` columns,
loading/pagination state, `loadMore`, `total`, record opening callbacks, and the optional hosted
`toolbar`. It also receives:

- `selectedIds` and `setSelection(ids)` for controlled row selection;
- `selectAll()` for the same scoped, cursor-paginated all-matching selection as the standard list;
- `clearSelection()`, `selectingAll`, and `allSelected` (`true`, `false`, or `"indeterminate"`).

Use these callbacks rather than fetching ids independently or maintaining another selection set.
The default toolbar and batch actions see the replacement's selection, including unloaded rows.
Query changes cancel pending select-all and clear selection through the existing controller.
`ListStateProps` exposes search, sort, view, grouping and selection for a host that owns controls;
`EntityListView` can pass its standard toolbar into the renderer with `toolbarPlacement="renderer"`.
The list controller remains hosted by `EntityListView`; a standalone `useEntityList` hook is not
currently part of the public API.

See the example's `components/ReceiptNotesEditor.tsx` for a replacement using only SDK imports.
Use the [Vite development workflow](RUNNING.md#intended-development-workflow) to edit replacements.
