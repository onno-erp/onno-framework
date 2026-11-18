import { afterEach, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";

const { updateCatalogItem, validateRecord } = vi.hoisted(() => ({
  updateCatalogItem: vi.fn(),
  validateRecord: vi.fn(),
}));

vi.mock("@/lib/api", () => ({
  api: {
    updateCatalogItem,
    validateRecord,
  },
  ApiError: class ApiError extends Error {},
}));

vi.mock("@/components/fields/date-picker", () => ({ DatePicker: () => null }));
vi.mock("@/components/maps/map-editor", () => ({ MapEditor: () => null }));
vi.mock("@/components/fields/image-picker", () => ({ ImagePicker: () => null, GalleryPicker: () => null }));
vi.mock("@/components/fields/file-picker", () => ({ FilePicker: () => null }));
vi.mock("@/components/actions/record-actions", () => ({ RecordActions: () => null }));

import { RecordForm } from "@/components/entities/record-form";

afterEach(() => {
  cleanup();
  updateCatalogItem.mockReset();
  validateRecord.mockReset();
});


import { AppConfigProvider } from "@/lib/app-config";
import type { UiExtension } from "@/lib/extension-types";
it("installs the app's header action and right-panel content on an ordinary entity form", () => {
  const extensions: UiExtension[] = [
    {id:"test.header",slot:"entity.form.actions",visible:context=>context.name==="palettes",component:({context})=><button>Inspect {context.recordId}</button>},
    {id:"test.aside",slot:"entity.form.aside",component:({context})=><aside>Extension: {String(context.record?.description)}</aside>},
  ];
  render(<AppConfigProvider app={{ extensions }}><RecordForm form={{kind:"catalogs",name:"palettes",id:"one",meta:{name:"Palettes",attributes:[]},initial:{_id:"one",description:"Ocean"}}} /></AppConfigProvider>);
  expect(screen.getByRole("button",{name:"Inspect one"})).toBeInTheDocument();
  expect(screen.getByText("Extension: Ocean")).toBeInTheDocument();
});
