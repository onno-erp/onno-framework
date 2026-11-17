import type { ReactNode } from "react";
import { useCustomizedForm, useCustomizedList, useEntityCustomizations } from "../lib/app-config";
import { EntityListView, type ListDescriptor } from "../components/lists/entity-list";
import { RecordForm, formTitle, type FormDescriptor } from "../components/entities/record-form";
import { RegisterSurface } from "../components/lists/register-surface";
import { CommentThread } from "../components/collaboration/comments";
import type { ViewModel, ViewNode } from "../lib/view-types";
import { cn } from "../lib/utils";
import { Boundary } from "../components/pages/boundary";
import { useModel } from "../lib/model-store";
import { useMessages } from "../providers/messages-provider";

/**
 * Renders a server view (`GET /api/ui/view/**`) as React. The view is only the surface's data; the
 * page around it is drawn here: lists and registers full-bleed, a record padded, with its comments
 * thread under the form when the comments module is on and the entity's customization asks for it
 * (`comments: true`).
 *
 * Every data-owning node mounts inside a {@link Boundary}: an error boundary, so a throwing renderer
 * never unwinds the page.
 */
export type RouteViewProps = {
  view: ViewModel;
};

export function RouteView({ view }: RouteViewProps) {
  // The app's entity customizations are keyed by logical name and resolved through the model, so
  // render once it is in: a list mounted before then would settle on its uncustomized defaults
  // (e.g. the table instead of the app's default view).
  const model = useModel();
  if (!model) return null;
  const record = "form" in view;
  return (
    <div className={cn("flex w-full flex-col gap-2", record && "p-4")}>
      {"form" in view ? (
        <RecordView form={view.form as unknown as FormDescriptor} comments={model.features.comments} />
      ) : "list" in view ? (
        <ViewNodeView node={{ type: "list", list: view.list }} />
      ) : (
        <ViewNodeView node={{ type: "register", register: view.register }} />
      )}
    </div>
  );
}

/** A record form and, when enabled for its entity, the record's comments thread under it. */
function RecordView({ form, comments }: { form: FormDescriptor; comments: boolean }) {
  const customizations = useEntityCustomizations(form.kind, form.name);
  const showComments = comments && !!form.id && !form.duplicate && customizations.some((c) => c.comments);
  return (
    <>
      <ViewNodeView node={{ type: "form", form: form as unknown as Record<string, unknown> }} />
      {showComments ? (
        <ViewNodeView node={{ type: "comments", target: { kind: form.kind, name: form.name, id: form.id! } }} />
      ) : null}
    </>
  );
}

/** A default list with the app's customizations for its entity applied. */
function CustomizedList({ list }: { list: ListDescriptor }) {
  const customized = useCustomizedList(list)!;
  return <Boundary label={customized.title || customized.name}><EntityListView list={customized} /></Boundary>;
}

/** A default record form with the app's customizations for its entity applied. */
function CustomizedForm({ form }: { form: FormDescriptor }) {
  const customized = useCustomizedForm(form);
  const t = useMessages();
  return <Boundary label={formTitle(customized, t)}><RecordForm form={customized} /></Boundary>;
}

/** The page header card: title, optional subtitle, optional controls on the right (`actions`). */
export function PageHeader({ title, subtitle, actions }: { title: string; subtitle?: string; actions?: ReactNode }) {
  return (
    <header
      data-onno-page-header=""
      className="mb-1 flex w-full items-center gap-3 rounded-card border border-border bg-card px-4 py-3"
    >
      <div className="min-w-0 flex-1">
        <h1 className="truncate text-2xl font-medium text-foreground">{title}</h1>
        {subtitle ? <p className="mt-0.5 text-[13px] text-muted-foreground">{subtitle}</p> : null}
      </div>
      {actions ? <div className="flex shrink-0 items-center gap-2">{actions}</div> : null}
    </header>
  );
}

/** One component node. Unknown node types render nothing (forward compatible). */
export function ViewNodeView({ node }: { node: ViewNode }): ReactNode {
  switch (node.type) {
    case "list":
      return <CustomizedList list={node.list as unknown as ListDescriptor} />;
    case "form":
      return <CustomizedForm form={node.form as unknown as FormDescriptor} />;
    case "register":
      return (
        <Boundary label={String(node.register.title ?? "")}>
          <RegisterSurface register={node.register as never} />
        </Boundary>
      );
    case "comments":
      return <Boundary label="Comments"><CommentThread target={node.target as never} /></Boundary>;
    default:
      return null;
  }
}

