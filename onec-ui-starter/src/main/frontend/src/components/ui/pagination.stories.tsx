import { useState } from "react";
import type { Meta, StoryObj } from "@storybook/react";
import { Pagination } from "./pagination";

const meta: Meta<typeof Pagination> = {
  title: "Primitives/Pagination",
  component: Pagination,
  parameters: { layout: "padded" },
  decorators: [
    (Story) => (
      <div className="w-[44rem] rounded-md border bg-background">
        <Story />
      </div>
    ),
  ],
};

export default meta;
type Story = StoryObj<typeof Pagination>;

export const Default: Story = {
  render: () => {
    const [page, setPage] = useState(0);
    const [pageSize, setPageSize] = useState(25);
    return (
      <Pagination
        page={page}
        pageSize={pageSize}
        total={427}
        onPageChange={setPage}
        onPageSizeChange={setPageSize}
      />
    );
  },
};

export const Empty: Story = {
  render: () => (
    <Pagination
      page={0}
      pageSize={25}
      total={0}
      onPageChange={() => {}}
      onPageSizeChange={() => {}}
    />
  ),
};

export const SinglePage: Story = {
  render: () => (
    <Pagination
      page={0}
      pageSize={25}
      total={6}
      onPageChange={() => {}}
      onPageSizeChange={() => {}}
    />
  ),
};
