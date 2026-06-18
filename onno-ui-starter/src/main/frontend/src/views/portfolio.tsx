import {
  ArrowUpRight,
  Braces,
  CircuitBoard,
  Github,
  Layers3,
  Mail,
  Rocket,
  Sparkles,
} from "lucide-react";
import heroImage from "@/assets/micha-hero.png";
import { buttonVariants } from "@/components/ui/button";
import { cn } from "@/lib/utils";

const projects = [
  {
    name: "Signal Studio",
    type: "Product Strategy",
    description: "A fast-moving command center for turning messy business inputs into crisp product decisions.",
    metrics: "42% faster discovery",
  },
  {
    name: "Atlas Runtime",
    type: "Engineering System",
    description: "A modular app architecture with clean boundaries, observable workflows, and calm operator UX.",
    metrics: "8 core modules",
  },
  {
    name: "Northstar AI",
    type: "AI Interface",
    description: "A human-centered assistant surface for research, synthesis, and high-trust decision support.",
    metrics: "3 min to insight",
  },
];

const capabilities = [
  { label: "Full-stack apps", icon: Layers3 },
  { label: "AI workflows", icon: Sparkles },
  { label: "Systems design", icon: CircuitBoard },
  { label: "Launch strategy", icon: Rocket },
];

export function PortfolioPage() {
  return (
    <div className="min-h-screen bg-[#08090b] text-white">
      <nav className="fixed left-0 right-0 top-0 z-30 border-b border-white/10 bg-[#08090b]/70 backdrop-blur-xl">
        <div className="mx-auto flex h-16 max-w-7xl items-center justify-between px-4 sm:px-6 lg:px-8">
          <a href="#top" className="flex items-center gap-3" aria-label="Micha portfolio home">
            <span className="flex h-9 w-9 items-center justify-center rounded-md border border-white/15 bg-white text-sm font-black text-[#08090b]">
              M
            </span>
            <span className="text-sm font-semibold uppercase tracking-[0.22em] text-white/75">Micha</span>
          </a>
          <div className="hidden items-center gap-6 text-sm text-white/62 sm:flex">
            <a className="transition hover:text-white" href="#work">Work</a>
            <a className="transition hover:text-white" href="#craft">Craft</a>
            <a className="transition hover:text-white" href="#contact">Contact</a>
          </div>
          <a
            href="mailto:micha@example.com"
            className={cn(
              buttonVariants({ size: "sm" }),
              "border border-white/15 bg-white text-[#08090b] hover:bg-[#f4f0df]"
            )}
          >
            <Mail className="h-4 w-4" />
            Contact
          </a>
        </div>
      </nav>

      <header id="top" className="relative min-h-[92svh] overflow-hidden pt-16">
        <img
          src={heroImage}
          alt=""
          className="absolute inset-0 h-full w-full object-cover object-center"
          aria-hidden="true"
        />
        <div className="absolute inset-0 bg-[linear-gradient(90deg,#08090b_0%,rgba(8,9,11,0.88)_31%,rgba(8,9,11,0.35)_66%,rgba(8,9,11,0.72)_100%)]" />
        <div className="absolute inset-0 bg-[radial-gradient(circle_at_22%_82%,rgba(255,104,82,0.22),transparent_30%),radial-gradient(circle_at_72%_28%,rgba(72,217,255,0.20),transparent_31%)]" />

        <div className="relative mx-auto flex min-h-[calc(92svh-4rem)] max-w-7xl items-center px-4 py-20 sm:px-6 lg:px-8">
          <div className="max-w-3xl">
            <div className="mb-6 inline-flex items-center gap-2 rounded-md border border-white/12 bg-white/7 px-3 py-2 text-xs font-medium uppercase tracking-[0.22em] text-[#7ee8ff] backdrop-blur-md">
              <Braces className="h-4 w-4" />
              Builder / Designer / Systems Thinker
            </div>
            <h1 className="max-w-3xl text-6xl font-black leading-[0.86] tracking-normal text-white sm:text-7xl md:text-8xl lg:text-9xl">
              Micha builds sharp digital things.
            </h1>
            <p className="mt-8 max-w-2xl text-lg leading-8 text-white/72 sm:text-xl">
              Modern products, intelligent workflows, and interfaces with taste. I turn fuzzy ambition into software
              that feels precise, fast, and a little bit cinematic.
            </p>
            <div className="mt-10 flex flex-col gap-3 sm:flex-row">
              <a
                href="#work"
                className={cn(
                  buttonVariants({ size: "lg" }),
                  "bg-[#7ee8ff] text-[#071014] hover:bg-[#a8f1ff]"
                )}
              >
                View Work
                <ArrowUpRight className="h-4 w-4" />
              </a>
              <a
                href="#contact"
                className={cn(
                  buttonVariants({ size: "lg", variant: "outline" }),
                  "border-white/18 bg-white/8 text-white hover:bg-white/14 hover:text-white"
                )}
              >
                Start a Project
              </a>
            </div>
          </div>
        </div>
      </header>

      <main>
        <section className="border-y border-white/10 bg-[#f4f0df] text-[#111316]">
          <div className="mx-auto grid max-w-7xl gap-6 px-4 py-7 sm:grid-cols-4 sm:px-6 lg:px-8">
            {capabilities.map((item) => (
              <div key={item.label} className="flex items-center gap-3">
                <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-md bg-[#111316] text-[#f5c84b]">
                  <item.icon className="h-5 w-5" />
                </span>
                <span className="text-sm font-bold uppercase tracking-[0.14em]">{item.label}</span>
              </div>
            ))}
          </div>
        </section>

        <section id="work" className="mx-auto max-w-7xl px-4 py-24 sm:px-6 lg:px-8">
          <div className="mb-10 flex flex-col justify-between gap-5 sm:flex-row sm:items-end">
            <div>
              <p className="text-sm font-semibold uppercase tracking-[0.22em] text-[#ff765f]">Selected Work</p>
              <h2 className="mt-3 max-w-2xl text-4xl font-black leading-none text-white sm:text-5xl">
                Digital products with a pulse.
              </h2>
            </div>
            <p className="max-w-md text-sm leading-6 text-white/58">
              A sample portfolio direction for Micha: strategic, technical, and visually memorable without getting
              trapped in portfolio-template energy.
            </p>
          </div>

          <div className="grid gap-4 lg:grid-cols-3">
            {projects.map((project, index) => (
              <article
                key={project.name}
                className="group min-h-[360px] rounded-lg border border-white/10 bg-white/[0.045] p-6 transition duration-300 hover:-translate-y-1 hover:border-[#7ee8ff]/50 hover:bg-white/[0.07]"
              >
                <div className="flex items-start justify-between gap-4">
                  <span className="text-sm font-semibold text-[#7ee8ff]">{project.type}</span>
                  <span className="text-5xl font-black leading-none text-white/10">0{index + 1}</span>
                </div>
                <h3 className="mt-20 text-3xl font-black tracking-normal text-white">{project.name}</h3>
                <p className="mt-4 text-sm leading-6 text-white/62">{project.description}</p>
                <div className="mt-8 flex items-center justify-between border-t border-white/10 pt-5">
                  <span className="text-sm font-semibold text-[#f5c84b]">{project.metrics}</span>
                  <ArrowUpRight className="h-5 w-5 text-white/45 transition group-hover:translate-x-1 group-hover:-translate-y-1 group-hover:text-white" />
                </div>
              </article>
            ))}
          </div>
        </section>

        <section id="craft" className="bg-[#e7fff6] text-[#111316]">
          <div className="mx-auto grid max-w-7xl gap-10 px-4 py-24 sm:px-6 lg:grid-cols-[0.9fr_1.1fr] lg:px-8">
            <div>
              <p className="text-sm font-semibold uppercase tracking-[0.22em] text-[#087a68]">Craft</p>
              <h2 className="mt-3 text-4xl font-black leading-none sm:text-5xl">Clean systems. Loud outcomes.</h2>
            </div>
            <div className="grid gap-4 sm:grid-cols-2">
              {[
                ["01", "Design interfaces that make complex work feel obvious."],
                ["02", "Build resilient frontends and backend flows that can scale."],
                ["03", "Shape AI-assisted workflows around real user judgment."],
                ["04", "Launch with polish, instrumentation, and room to evolve."],
              ].map(([number, text]) => (
                <div key={number} className="rounded-lg border border-[#111316]/12 bg-white/70 p-6">
                  <div className="text-sm font-black text-[#ff765f]">{number}</div>
                  <p className="mt-5 text-xl font-bold leading-7">{text}</p>
                </div>
              ))}
            </div>
          </div>
        </section>
      </main>

      <footer id="contact" className="mx-auto max-w-7xl px-4 py-20 sm:px-6 lg:px-8">
        <div className="flex flex-col gap-8 border-t border-white/10 pt-10 md:flex-row md:items-end md:justify-between">
          <div>
            <p className="text-sm font-semibold uppercase tracking-[0.22em] text-white/45">Available for sharp ideas</p>
            <h2 className="mt-3 max-w-2xl text-4xl font-black leading-none text-white sm:text-6xl">
              Let’s make something unmistakably Micha.
            </h2>
          </div>
          <div className="flex flex-col gap-3 sm:flex-row">
            <a
              href="mailto:micha@example.com"
              className={cn(buttonVariants({ size: "lg" }), "bg-white text-[#08090b] hover:bg-[#f4f0df]")}
            >
              <Mail className="h-4 w-4" />
              Email Micha
            </a>
            <a
              href="https://github.com/"
              target="_blank"
              rel="noreferrer"
              className={cn(
                buttonVariants({ size: "lg", variant: "outline" }),
                "border-white/18 bg-transparent text-white hover:bg-white/10 hover:text-white"
              )}
            >
              <Github className="h-4 w-4" />
              GitHub
            </a>
          </div>
        </div>
      </footer>
    </div>
  );
}
