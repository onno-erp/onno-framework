export interface EnumValue {
  name: string;
  id: string;
}

export interface AttributeMeta {
  fieldName: string;
  displayName: string;
  columnName: string;
  javaType: string;
  length: number;
  required: boolean;
  isRef: boolean;
  refTarget?: string;
  isEnum: boolean;
  enumName?: string;
  enumValues?: EnumValue[];
  precision: number;
  scale: number;
  visibleInList: boolean;
  visibleInForm: boolean;
  visibleInDetail: boolean;
  order: number;
  group: string;
  widthHint: string;
  widget: string;
}

export interface TabularSectionMeta {
  name: string;
  tableName: string;
  attributes: AttributeMeta[];
}

export interface CatalogMeta {
  name: string;
  tableName: string;
  codeLength: number;
  attributes: AttributeMeta[];
  section?: string;
  sectionOrder?: number;
}

export interface DocumentMeta {
  name: string;
  tableName: string;
  numberLength: number;
  attributes: AttributeMeta[];
  tabularSections: TabularSectionMeta[];
  section?: string;
  sectionOrder?: number;
}

export interface RegisterMeta {
  name: string;
  tableName: string;
  type: "BALANCE" | "TURNOVER";
  dimensions: AttributeMeta[];
  resources: AttributeMeta[];
  section?: string;
  sectionOrder?: number;
}

export interface AppConfig {
  readOnly: boolean;
  basePath: string;
}

export interface DashboardWidgetMeta {
  title: string;
  widgetType: string;
  order: number;
  width: string;
  entityType: string;
  entityName: string;
  maxItems: number;
  dateField: string;
  titleField: string;
  extraConfig: Record<string, string>;
}

export interface LayoutItem {
  name: string;
  type: string;
  href: string;
}

export interface LayoutSection {
  name: string;
  order: number;
  icon: string;
  placement: string;
  items: LayoutItem[];
}

export type EntityRecord = Record<string, unknown>;
