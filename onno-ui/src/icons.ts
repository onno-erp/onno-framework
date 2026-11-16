/** Tree-shaken Lucide components for app pages, navigation, and commands. */
export * from "lucide-react";
import type { LucideIcon } from "lucide-react";
import type { ComponentType } from "react";
import { Icon as IconImpl } from "./lib/icons";
export type IconProps = { icon: LucideIcon; size?: number; color?: string; strokeWidth?: number; className?: string };
export const Icon = IconImpl as ComponentType<IconProps>;
