import {
  useEffect,
  useSyncExternalStore,
  type ComponentType,
} from "react";
import { createPortal } from "react-dom";
import { IslandErrorBoundary } from "@/lib/island-error-boundary";

export type UiFeatureRuntime = {
  focusedPath: string;
  navStyle: "topbar" | "sidebar" | "bottom_bar" | "unknown";
};

export type UiFeatureCustomProps = {
  payload?: unknown;
};

export type UiFeatureRegistration = {
  id: string;
  customComponents?: Record<string, ComponentType<UiFeatureCustomProps>>;
  Root?: ComponentType<UiFeatureRuntime>;
  TabAdornment?: ComponentType<{ path: string }>;
  SidebarFooter?: ComponentType<{ background: string; borderColor: string }>;
  RowAdornment?: ComponentType<{ kind: string; name: string; rowId: string }>;
  handleAction?: (url: string) => boolean;
};

type Mount = {
  id: number;
  el: HTMLElement;
  customType: string;
  payload: unknown;
};

let registrations: UiFeatureRegistration[] = [];
let mounts: Mount[] = [];
let mountSequence = 0;
let runtime: UiFeatureRuntime = { focusedPath: "", navStyle: "unknown" };
const listeners = new Set<() => void>();

function emit() {
  for (const listener of listeners) listener();
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

function registrationsSnapshot(): UiFeatureRegistration[] {
  return registrations;
}

function mountsSnapshot(): Mount[] {
  return mounts;
}

function runtimeSnapshot(): UiFeatureRuntime {
  return runtime;
}

function defineFeatureElement(customType: string) {
  if (typeof customElements === "undefined" || customElements.get(customType)) return;
  customElements.define(
    customType,
    class extends HTMLElement {
      private readonly mountId = ++mountSequence;
      private value: unknown;

      set payload(payload: unknown) {
        this.value = payload;
        this.sync();
      }

      get payload(): unknown {
        return this.value;
      }

      connectedCallback() {
        this.dataset.onnoUiFeature = customType;
        this.sync();
      }

      disconnectedCallback() {
        if (!mounts.some((mount) => mount.el === this)) return;
        mounts = mounts.filter((mount) => mount.el !== this);
        emit();
      }

      private sync() {
        if (!this.isConnected) return;
        const current = mounts.find((mount) => mount.el === this);
        if (current) {
          current.payload = this.value;
          mounts = [...mounts];
        } else {
          mounts = [
            ...mounts,
            { id: this.mountId, el: this, customType, payload: this.value },
          ];
        }
        emit();
      }
    }
  );
}

/** Register an opt-in UI feature. Last registration wins for duplicate feature ids. */
export function registerUiFeature(feature: UiFeatureRegistration): void {
  if (!feature.id?.trim()) throw new Error("An onno UI feature requires a stable id");
  for (const customType of Object.keys(feature.customComponents ?? {})) {
    defineFeatureElement(customType);
  }
  registrations = [
    ...registrations.filter((registered) => registered.id !== feature.id),
    feature,
  ];
  emit();
}

/** DivKit custom-type mappings contributed by every installed UI feature. */
export function getUiFeatureCustomComponents(): Map<string, { element: string }> {
  const out = new Map<string, { element: string }>();
  for (const feature of registrations) {
    for (const customType of Object.keys(feature.customComponents ?? {})) {
      out.set(customType, { element: customType });
    }
  }
  return out;
}

export function hasUiFeatureRowAdornments(): boolean {
  return registrations.some((feature) => feature.RowAdornment);
}

export function handleUiFeatureAction(url: string): boolean {
  return registrations.some((feature) => feature.handleAction?.(url) === true);
}

export function setUiFeatureRuntime(next: UiFeatureRuntime): void {
  if (runtime.focusedPath === next.focusedPath && runtime.navStyle === next.navStyle) return;
  runtime = next;
  emit();
}

export function UiFeaturePortals() {
  const liveMounts = useSyncExternalStore(subscribe, mountsSnapshot);
  const liveFeatures = useSyncExternalStore(subscribe, registrationsSnapshot);
  return (
    <>
      {liveMounts.map((mount) => {
        const feature = liveFeatures.find(
          (candidate) => candidate.customComponents?.[mount.customType]
        );
        const Component = feature?.customComponents?.[mount.customType];
        if (!Component) return null;
        return createPortal(
          <IslandErrorBoundary label={`${feature.id}:${mount.customType}`}>
            <Component payload={mount.payload} />
          </IslandErrorBoundary>,
          mount.el,
          String(mount.id)
        );
      })}
    </>
  );
}

export function UiFeatureRoots() {
  const features = useSyncExternalStore(subscribe, registrationsSnapshot);
  const { focusedPath, navStyle } = useSyncExternalStore(subscribe, runtimeSnapshot);
  return (
    <>
      {features.map(({ id, Root }) =>
        Root ? (
          <IslandErrorBoundary key={id} label={id}>
            <Root focusedPath={focusedPath} navStyle={navStyle} />
          </IslandErrorBoundary>
        ) : null
      )}
    </>
  );
}

export function UiFeatureRuntimeBridge({ focusedPath, navStyle }: UiFeatureRuntime) {
  useEffect(() => setUiFeatureRuntime({ focusedPath, navStyle }), [focusedPath, navStyle]);
  return <UiFeatureRoots />;
}

export function UiFeatureTabAdornments({ path }: { path: string }) {
  const features = useSyncExternalStore(subscribe, registrationsSnapshot);
  return (
    <>
      {features.map(({ id, TabAdornment }) =>
        TabAdornment ? <TabAdornment key={id} path={path} /> : null
      )}
    </>
  );
}

export function UiFeatureSidebarFooters({
  background,
  borderColor,
}: {
  background: string;
  borderColor: string;
}) {
  const features = useSyncExternalStore(subscribe, registrationsSnapshot);
  return (
    <>
      {features.map(({ id, SidebarFooter }) =>
        SidebarFooter ? (
          <SidebarFooter key={id} background={background} borderColor={borderColor} />
        ) : null
      )}
    </>
  );
}

export function UiFeatureRowAdornments({
  kind,
  name,
  rowId,
}: {
  kind: string;
  name: string;
  rowId: string;
}) {
  const features = useSyncExternalStore(subscribe, registrationsSnapshot);
  return (
    <>
      {features.map(({ id, RowAdornment }) =>
        RowAdornment ? (
          <RowAdornment key={id} kind={kind} name={name} rowId={rowId} />
        ) : null
      )}
    </>
  );
}
