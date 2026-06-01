import type { Meta, StoryObj } from "@storybook/react";
import { Route, Routes } from "react-router-dom";
import { CatalogListView } from "./catalog-list";

const meta: Meta<typeof CatalogListView> = {
  title: "Views/CatalogListView",
  component: CatalogListView,
  parameters: {
    layout: "fullscreen",
    router: { initialEntries: ["/catalogs/Products"] },
  },
  decorators: [
    (Story) => (
      <div className="min-h-screen bg-background p-6">
        <Routes>
          <Route path="/catalogs/:name" element={<Story />} />
        </Routes>
      </div>
    ),
  ],
};

export default meta;
type Story = StoryObj<typeof CatalogListView>;

export const Products: Story = {};
