import type { StorybookConfig } from "@storybook/react-vite";
import path from "path";
import { fileURLToPath } from "url";

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, "..");

const config: StorybookConfig = {
  stories: ["../src/**/*.stories.@(ts|tsx|mdx)"],
  addons: [
    "@storybook/addon-essentials",
    "@storybook/addon-interactions",
  ],
  framework: {
    name: "@storybook/react-vite",
    options: {},
  },
  docs: { autodocs: "tag" },
  typescript: { reactDocgen: false },
  async viteFinal(config) {
    config.resolve = config.resolve ?? {};
    const apiMock = path.resolve(here, "./api-mock.ts");
    const themeProvider = path.resolve(here, "./theme-provider.tsx");
    const srcRoot = path.resolve(root, "./src");
    config.resolve.alias = [
      { find: /^@\/lib\/api$/, replacement: apiMock },
      { find: /^@\/providers\/theme-provider$/, replacement: themeProvider },
      { find: /^@\//, replacement: srcRoot + "/" },
    ];
    return config;
  },
};

export default config;
