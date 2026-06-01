import { Avatar, AvatarImage, AvatarFallback } from "@/components/ui/avatar";
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from "@/components/ui/tooltip";
import { cn } from "@/lib/utils";

export interface ActivityActor {
  id: string;
  name: string;
  imageUrl?: string;
  /** Optional secondary description shown in the tooltip, e.g. "edited 2h ago". */
  description?: string;
}

interface AvatarStackProps {
  actors: ActivityActor[];
  /** Maximum avatars to show before collapsing into a "+N" badge. */
  max?: number;
  size?: "xs" | "sm" | "md";
  className?: string;
}

const sizeMap: Record<NonNullable<AvatarStackProps["size"]>, string> = {
  xs: "h-5 w-5 text-[9px]",
  sm: "h-6 w-6 text-[10px]",
  md: "h-8 w-8 text-[11px]",
};

const ringMap: Record<NonNullable<AvatarStackProps["size"]>, string> = {
  xs: "ring-1",
  sm: "ring-2",
  md: "ring-2",
};

const overlapMap: Record<NonNullable<AvatarStackProps["size"]>, string> = {
  xs: "-ml-1.5",
  sm: "-ml-2",
  md: "-ml-2.5",
};

function initials(name: string): string {
  const parts = name.trim().split(/[\s._-]+/).filter(Boolean);
  if (parts.length >= 2) return (parts[0][0] + parts[1][0]).toUpperCase();
  return (name.trim().slice(0, 2) || "?").toUpperCase();
}

export function AvatarStack({
  actors,
  max = 4,
  size = "sm",
  className,
}: AvatarStackProps) {
  if (actors.length === 0) return null;
  const visible = actors.slice(0, max);
  const overflow = actors.length - visible.length;

  return (
    <TooltipProvider delayDuration={200}>
      <div className={cn("flex items-center", className)}>
        {visible.map((actor, i) => (
          <Tooltip key={actor.id}>
            <TooltipTrigger asChild>
              <Avatar
                className={cn(
                  sizeMap[size],
                  ringMap[size],
                  "ring-background transition-transform hover:-translate-y-0.5",
                  i > 0 && overlapMap[size]
                )}
                style={{ zIndex: visible.length - i }}
              >
                {actor.imageUrl && <AvatarImage src={actor.imageUrl} alt={actor.name} />}
                <AvatarFallback>{initials(actor.name)}</AvatarFallback>
              </Avatar>
            </TooltipTrigger>
            <TooltipContent>
              <div className="text-xs font-medium">{actor.name}</div>
              {actor.description && (
                <div className="text-[11px] text-muted-foreground">{actor.description}</div>
              )}
            </TooltipContent>
          </Tooltip>
        ))}
        {overflow > 0 && (
          <Tooltip>
            <TooltipTrigger asChild>
              <Avatar
                className={cn(
                  sizeMap[size],
                  ringMap[size],
                  "ring-background",
                  overlapMap[size]
                )}
                style={{ zIndex: 0 }}
              >
                <AvatarFallback className="bg-muted text-muted-foreground">
                  +{overflow}
                </AvatarFallback>
              </Avatar>
            </TooltipTrigger>
            <TooltipContent>
              <div className="text-xs">
                {actors
                  .slice(max)
                  .map((a) => a.name)
                  .join(", ")}
              </div>
            </TooltipContent>
          </Tooltip>
        )}
      </div>
    </TooltipProvider>
  );
}
