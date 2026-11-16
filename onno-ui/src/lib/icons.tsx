import { Suspense, lazy, type ComponentType } from "react";
import dynamicIconImports from "lucide-react/dynamicIconImports";
import { Circle, type LucideIcon } from "lucide-react";

/**
 * Internal renderer for imported Lucide components and the shell's existing named defaults.
 * The public app API accepts components; name lookup is kept inside the framework renderer.
 */
export type IconComponent = Pick<LucideIcon, "$$typeof">;
export type IconSource = string | IconComponent;

type LucideProps = { color?: string; size?: number | string; strokeWidth?: number | string; className?: string };
type IconLoader = () => Promise<{ default: ComponentType<LucideProps> }>;

// dynamicIconImports maps every kebab-case lucide name to a lazy import of that icon, so a name
// only loads its own icon.
const IMPORTS = dynamicIconImports as unknown as Record<string, IconLoader>;
const lazyCache = new Map<string, ComponentType<LucideProps>>();

function lucideFor(name: string): ComponentType<LucideProps> | null {
  const loader = IMPORTS[name];
  if (!loader) return null;
  let Component = lazyCache.get(name);
  if (!Component) {
    Component = lazy(loader);
    lazyCache.set(name, Component);
  }
  return Component;
}

export interface IconProps {
  /** Internal named defaults or a resolved component. Public consumers use `icon`. */
  name?: IconSource;
  icon?: IconSource;
  /** Pixel size (width and height). Default 16. */
  size?: number;
  color?: string;
  strokeWidth?: number;
  className?: string;
}

/**
 * Renders a lucide icon by its kebab-case `name`. Unknown names fall back to a circle so an icon is
 * never blank, and while the icon loads a same-size spacer keeps the layout from shifting.
 */
export function Icon({ name, icon, size = 16, color, strokeWidth, className }: IconProps) {
  name = icon ?? name ?? "circle";
  const Component = typeof name === "string" ? lucideFor(name) : name as LucideIcon;
  const props = { color, size, strokeWidth, className };
  if (!Component) return <Circle {...props} />;
  return (
    <Suspense fallback={<span className={className} style={{ display: "inline-block", width: size, height: size }} />}>
      <Component {...props} />
    </Suspense>
  );
}
