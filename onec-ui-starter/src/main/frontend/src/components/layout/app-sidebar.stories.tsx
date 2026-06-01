import type { Meta, StoryObj } from "@storybook/react";
import { AppSidebar } from "./app-sidebar";

const meta: Meta<typeof AppSidebar> = {
  title: "Layout/AppSidebar",
  component: AppSidebar,
  parameters: { layout: "fullscreen" },
  decorators: [
    (Story) => (
      <div className="h-screen flex bg-background">
        <Story />
        <main className="flex-1 p-6 text-sm text-muted-foreground">
          page content goes here
        </main>
      </div>
    ),
  ],
};

export default meta;
type Story = StoryObj<typeof AppSidebar>;

export const Default: Story = {};
