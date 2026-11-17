import { useAggregate } from "../lib/query";
import { count } from "../lib/measure";
import { Icon } from "../lib/icons";
import { openRoute } from "../lib/navigation";
import { useModel } from "../lib/model-store";
import { useMessages } from "../providers/messages-provider";
import { useBranding } from "../providers/branding-provider";
import { PageHeader } from "./route-view";
import type { ModelEntity } from "../lib/app-model";
import { entityIcon, useAppConfig } from "../lib/app-config";

/**
 * The home page an app gets with no configuration: a greeting, then the model's entities grouped
 * by bounded context as cards with live record counts. Replace it with `registerRoute("/", Home)`.
 */
export function DefaultHome() {
  const model = useModel();
  const { appName } = useBranding();
  const t = useMessages();
  if (!model) return null;
  const groups = new Map<string, ModelEntity[]>();
  for (const e of model.entities) {
    const key = e.context || "";
    groups.set(key, [...(groups.get(key) ?? []), e]);
  }
  return (
    <div className="flex w-full flex-col gap-2 p-4">
      <PageHeader
        title={appName || t("nav.dashboard")}
        subtitle={t("home.greeting", { name: model.user.displayName })}
      />
      {[...groups.entries()].map(([context, entities]) => (
        <section key={context} className="mt-2 flex flex-col gap-2">
          {context ? <h2 className="px-1 text-xs font-medium text-muted-foreground">{context}</h2> : null}
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-4">
            {entities.map((e) => (
              <EntityCard key={e.route} entity={e} />
            ))}
          </div>
        </section>
      ))}
    </div>
  );
}

function EntityCard({ entity }: { entity: ModelEntity }) {
  const { app } = useAppConfig();
  return (
    <button
      type="button"
      onClick={() => openRoute(entity.route)}
      className="flex items-center gap-3 rounded-card border border-border bg-card px-4 py-3 text-left transition-colors hover:bg-muted/50"
    >
      <span className="grid size-9 shrink-0 place-items-center rounded-field bg-muted text-muted-foreground">
        <Icon name={entityIcon(app, entity)} size={16} />
      </span>
      <span className="min-w-0 flex-1">
        <span className="block truncate text-sm font-medium">{entity.title}</span>
        <span className="block text-xs text-muted-foreground">
          {entity.kind === "registers" ? "Report" : <RecordCount entity={entity.logicalName} />}
        </span>
      </span>
    </button>
  );
}

/** How many records an entity has, live (refetched when the server reports a change to it). */
function RecordCount({ entity }: { entity: string }) {
  const { data } = useAggregate(count(entity));
  const value = data?.buckets[0]?.value;
  return <>{value == null ? " " : Number(value).toLocaleString()}</>;
}
