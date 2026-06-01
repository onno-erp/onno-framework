import type { Meta, StoryObj } from "@storybook/react";
import { LoginView } from "./login";
import { __setMockUser } from "../../.storybook/api-mock";

const meta: Meta<typeof LoginView> = {
  title: "Auth/LoginView",
  component: LoginView,
  parameters: {
    layout: "fullscreen",
    router: { initialEntries: ["/login"] },
  },
  beforeEach: () => {
    __setMockUser(null);
  },
};

export default meta;
type Story = StoryObj<typeof LoginView>;

export const SignIn: Story = {};
