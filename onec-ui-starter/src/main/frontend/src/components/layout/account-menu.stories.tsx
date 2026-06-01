import type { Meta, StoryObj } from "@storybook/react";
import { AccountMenu } from "./account-menu";
import { __setMockUser } from "../../../.storybook/api-mock";

const meta: Meta<typeof AccountMenu> = {
  title: "Layout/AccountMenu",
  component: AccountMenu,
  parameters: { layout: "padded" },
  decorators: [
    (Story) => (
      <div className="w-56 border border-border rounded-md bg-background p-2">
        <Story />
      </div>
    ),
  ],
};

export default meta;
type Story = StoryObj<typeof AccountMenu>;

export const Admin: Story = {
  beforeEach: () => {
    __setMockUser({
      authenticated: true,
      username: "admin",
      roles: ["ROLE_ADMIN"],
    });
  },
};

export const MultipleRoles: Story = {
  beforeEach: () => {
    __setMockUser({
      authenticated: true,
      username: "claire",
      roles: ["ROLE_SALES", "ROLE_WAREHOUSE", "ROLE_FINANCE"],
    });
  },
};

export const NoRoles: Story = {
  beforeEach: () => {
    __setMockUser({
      authenticated: true,
      username: "guest",
      roles: [],
    });
  },
};

export const LongUsername: Story = {
  beforeEach: () => {
    __setMockUser({
      authenticated: true,
      username: "rebecca.longworth-walters",
      roles: ["ROLE_OPERATIONS"],
    });
  },
};
