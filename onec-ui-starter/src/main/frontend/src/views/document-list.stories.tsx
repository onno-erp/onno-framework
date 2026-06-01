import type { Meta, StoryObj } from "@storybook/react";
import { Route, Routes } from "react-router-dom";
import { DocumentListView } from "./document-list";

const meta: Meta<typeof DocumentListView> = {
  title: "Views/DocumentList",
  component: DocumentListView,
  parameters: {
    layout: "fullscreen",
    router: { initialEntries: ["/documents/GoodsReceipt"] },
  },
  decorators: [
    (Story) => (
      <div className="min-h-screen bg-background p-6">
        <Routes>
          <Route path="/documents/:name" element={<Story />} />
        </Routes>
      </div>
    ),
  ],
};

export default meta;
type Story = StoryObj<typeof DocumentListView>;

export const GoodsReceipts: Story = {};
