import type { Meta, StoryObj } from "@storybook/react";
import { AvatarStack, type ActivityActor } from "./avatar-stack";

const meta: Meta<typeof AvatarStack> = {
  title: "Activity/AvatarStack",
  component: AvatarStack,
};

export default meta;
type Story = StoryObj<typeof AvatarStack>;

const team: ActivityActor[] = [
  { id: "1", name: "Anya Kuznetsova", description: "viewing now" },
  { id: "2", name: "Brian Okafor", description: "edited 3 min ago" },
  { id: "3", name: "Claire Liu", description: "edited 18 min ago" },
  { id: "4", name: "David Salazar", description: "viewed 1 h ago" },
  { id: "5", name: "Esme Nakamura", description: "edited yesterday" },
  { id: "6", name: "Felix Brandt", description: "edited last week" },
];

export const Default: Story = {
  args: { actors: team.slice(0, 3) },
};

export const WithOverflow: Story = {
  args: { actors: team, max: 4 },
};

export const Tiny: Story = {
  args: { actors: team, max: 5, size: "xs" },
};

export const Medium: Story = {
  args: { actors: team, max: 4, size: "md" },
};

export const SingleActor: Story = {
  args: { actors: team.slice(0, 1) },
};

export const InContext: Story = {
  render: () => (
    <div className="w-[420px] rounded-md border bg-background px-3 py-2.5 shadow-sm">
      <div className="flex items-center justify-between">
        <div>
          <div className="font-mono text-[11px] text-muted-foreground">INV-000001</div>
          <div className="text-sm font-medium">Main warehouse · $1,240.50</div>
        </div>
        <AvatarStack actors={team.slice(0, 3)} size="xs" />
      </div>
      <div className="mt-2 text-[11px] text-muted-foreground">
        Anya, Brian and Claire have viewed this invoice
      </div>
    </div>
  ),
};
