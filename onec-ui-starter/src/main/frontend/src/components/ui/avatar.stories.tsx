import type { Meta, StoryObj } from "@storybook/react";
import { Avatar, AvatarImage, AvatarFallback } from "./avatar";

const meta: Meta<typeof Avatar> = {
  title: "Primitives/Avatar",
  component: Avatar,
};

export default meta;
type Story = StoryObj<typeof Avatar>;

export const WithImage: Story = {
  render: () => (
    <Avatar>
      <AvatarImage
        src="https://i.pravatar.cc/64?u=admin"
        alt="admin"
      />
      <AvatarFallback>AD</AvatarFallback>
    </Avatar>
  ),
};

export const InitialsFallback: Story = {
  render: () => (
    <Avatar>
      <AvatarFallback>RB</AvatarFallback>
    </Avatar>
  ),
};

export const Sizes: Story = {
  render: () => (
    <div className="flex items-center gap-3">
      <Avatar className="h-5 w-5 text-[9px]"><AvatarFallback>XS</AvatarFallback></Avatar>
      <Avatar className="h-6 w-6 text-[10px]"><AvatarFallback>SM</AvatarFallback></Avatar>
      <Avatar className="h-9 w-9"><AvatarFallback>MD</AvatarFallback></Avatar>
      <Avatar className="h-12 w-12 text-sm"><AvatarFallback>LG</AvatarFallback></Avatar>
    </div>
  ),
};
