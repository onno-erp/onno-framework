import type { Meta, StoryObj } from "@storybook/react";
import { Popover, PopoverTrigger, PopoverContent } from "./popover";
import { Button } from "./button";

const meta: Meta = {
  title: "Primitives/Popover",
};

export default meta;
type Story = StoryObj;

export const Basic: Story = {
  render: () => (
    <Popover>
      <PopoverTrigger asChild>
        <Button variant="outline">Open</Button>
      </PopoverTrigger>
      <PopoverContent className="w-60">
        <p className="text-sm">Hi from inside the popover.</p>
      </PopoverContent>
    </Popover>
  ),
};
