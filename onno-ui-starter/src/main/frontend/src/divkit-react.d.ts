// Local shim: @divkitframework/react ships typings but its package.json "exports"
// map doesn't expose them to TS's resolver. Declare the slice of the API we use.
declare module "@divkitframework/react" {
  import type { ComponentType } from "react";

  export type DivKitProps = {
    id: string;
    json: unknown;
    onCustomAction?: (action: { url?: string; payload?: unknown }) => void;
    onStat?: (...args: unknown[]) => void;
    customComponents?: Map<string, unknown>;
    builtinProtocols?: string[];
    theme?: "system" | "light" | "dark";
    [key: string]: unknown;
  };

  export const DivKit: ComponentType<DivKitProps>;
}

declare module "@divkitframework/divkit/client-hydratable" {
  export interface DivKitVariable {
    setValue(value: unknown): void;
    set(value: string): void;
    getValue(): unknown;
  }
  export interface GlobalVariablesController {
    setVariable(variable: DivKitVariable): void;
    getVariable(name: string): DivKitVariable | undefined;
  }
  export function createVariable(name: string, type: string, value: unknown): DivKitVariable;
  export function createGlobalVariablesController(): GlobalVariablesController;
}
