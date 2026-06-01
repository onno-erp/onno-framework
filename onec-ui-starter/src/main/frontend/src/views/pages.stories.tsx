import type { Meta, StoryObj } from "@storybook/react";
import { Route, Routes } from "react-router-dom";
import { AppShell } from "@/components/layout/app-shell";
import { HomePage } from "./home";
import { CatalogListView } from "./catalog-list";
import { DocumentListView } from "./document-list";
import { RegisterReportView } from "./register-report";

const meta: Meta = {
  title: "Pages",
  parameters: { layout: "fullscreen" },
};
export default meta;
type Story = StoryObj;

const fullScreen = (children: React.ReactElement) => (
  <div className="min-h-screen bg-background">{children}</div>
);

export const Dashboard: Story = {
  parameters: { router: { initialEntries: ["/"] } },
  render: () =>
    fullScreen(
      <Routes>
        <Route element={<AppShell />}>
          <Route index element={<HomePage />} />
        </Route>
      </Routes>
    ),
};

export const CatalogList: Story = {
  parameters: { router: { initialEntries: ["/catalogs/products"] } },
  render: () =>
    fullScreen(
      <Routes>
        <Route element={<AppShell />}>
          <Route path="catalogs/:name" element={<CatalogListView />} />
        </Route>
      </Routes>
    ),
};

export const DocumentList: Story = {
  parameters: { router: { initialEntries: ["/documents/invoice"] } },
  render: () =>
    fullScreen(
      <Routes>
        <Route element={<AppShell />}>
          <Route path="documents/:name" element={<DocumentListView />} />
        </Route>
      </Routes>
    ),
};

export const RegisterReport: Story = {
  parameters: { router: { initialEntries: ["/registers/stock"] } },
  render: () =>
    fullScreen(
      <Routes>
        <Route element={<AppShell />}>
          <Route path="registers/:name" element={<RegisterReportView />} />
        </Route>
      </Routes>
    ),
};
