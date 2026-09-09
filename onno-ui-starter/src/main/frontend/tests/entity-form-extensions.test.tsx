import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";

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

vi.mock("@/components/date-picker", () => ({ DatePicker: () => null }));
vi.mock("@/components/map-editor", () => ({ MapEditor: () => null }));
vi.mock("@/components/image-picker", () => ({ ImagePicker: () => null, GalleryPicker: () => null }));
vi.mock("@/components/file-picker", () => ({ FilePicker: () => null }));
vi.mock("@/components/related-list-panel", () => ({ RelatedListPanel: () => null }));
vi.mock("@/lib/actions-menu-bridge", () => ({ ActionsCluster: () => null }));

import { EntityFormWidget, type FormDescriptor } from "@/components/entity-form-widget";

afterEach(() => {
  cleanup();
  updateCatalogItem.mockReset();
  validateRecord.mockReset();
});


import { registerExtension } from "@/lib/ui-extensions";
it("installs a header action and right-panel content on an ordinary entity form", () => {
  const remove = [
    registerExtension({id:"test.header",slot:"entity.form.actions",visible:context=>context.name==="palettes",component:({context})=><button>Inspect {context.recordId}</button>}),
    registerExtension({id:"test.aside",slot:"entity.form.aside",component:({context})=><aside>Extension: {String(context.record?.description)}</aside>}),
  ];
  try {
    render(<EntityFormWidget form={{kind:"catalogs",name:"palettes",id:"one",title:"Palette",submitLabel:"Save",meta:{name:"Palettes",attributes:[]},initial:{_id:"one",description:"Ocean"}}} />);
    expect(screen.getByRole("button",{name:"Inspect one"})).toBeInTheDocument();
    expect(screen.getByText("Extension: Ocean")).toBeInTheDocument();
  } finally { cleanup(); remove.forEach(fn=>fn()); }
});
