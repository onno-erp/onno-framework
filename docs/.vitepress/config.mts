import { defineConfig } from 'vitepress'

// Docs site for onno-framework. Hand-written guides live as Markdown alongside reference that is
// GENERATED from the source of truth: docs/CONFIGURATION.md (from the @ConfigurationProperties
// Javadoc, via `./gradlew generateConfigDocs`) and the aggregated Javadoc, staged into
// docs/public/api by the docs workflow (`./gradlew aggregateJavadoc`).
export default defineConfig({
  title: 'onno-framework',
  description:
    'Spring Boot starters that turn typed Java metadata into schema, REST API, UI, and MCP tools.',
  lang: 'en-US',

  // Served at the root of the custom domain https://docs.onno.su/ (see docs/public/CNAME), so the
  // base path is '/' — not the '/onno-framework/' a github.io project site would use.
  base: '/',
  cleanUrls: true,
  lastUpdated: true,
  sitemap: { hostname: 'https://docs.onno.su' },

  // Sidecar prose merged into the generated CONFIGURATION.md — fragments, not pages.
  srcExclude: ['_config/**'],

  // The guides intentionally cross-reference repo-root files (../AGENTS.md, ../onno-*/README.md) and
  // source files on GitHub, which aren't part of the site. Don't fail the build over those (the
  // pattern is unanchored because VitePress normalises these links to a `./../…` form). Genuine
  // in-site dead links are still caught.
  ignoreDeadLinks: [/\.\.\//, /\.java$/],

  themeConfig: {
    nav: [
      {
        text: 'Guides',
        items: [
          { text: 'Architecture', link: '/ARCHITECTURE' },
          { text: 'Extending onno', link: '/EXTENDING' },
          { text: 'Headless Read API', link: '/HEADLESS_READ_API' },
          { text: 'Media Uploads', link: '/MEDIA_UPLOADS' },
          {
            text: 'Building ERPs with AI agents',
            link: 'https://github.com/onno-erp/onno-framework/blob/main/BUILDING_ERPS_WITH_AGENTS.md',
          },
        ],
      },
      {
        text: 'Reference',
        items: [
          { text: 'Configuration properties', link: '/CONFIGURATION' },
          { text: 'Java API (Javadoc)', link: '/api/' },
        ],
      },
      {
        text: 'Roadmap',
        link: 'https://github.com/onno-erp/onno-framework/blob/main/ROADMAP.md',
      },
    ],

    sidebar: [
      {
        text: 'Introduction',
        items: [{ text: 'Overview', link: '/' }],
      },
      {
        text: 'Guides',
        items: [
          { text: 'Architecture', link: '/ARCHITECTURE' },
          { text: 'Extending onno', link: '/EXTENDING' },
          { text: 'Headless Read API', link: '/HEADLESS_READ_API' },
          { text: 'Media Uploads', link: '/MEDIA_UPLOADS' },
        ],
      },
      {
        text: 'Reference',
        items: [
          { text: 'Configuration properties', link: '/CONFIGURATION' },
          { text: 'Java API (Javadoc)', link: '/api/' },
        ],
      },
    ],

    socialLinks: [{ icon: 'github', link: 'https://github.com/onno-erp/onno-framework' }],

    search: { provider: 'local' },

    editLink: {
      pattern: 'https://github.com/onno-erp/onno-framework/edit/main/docs/:path',
      text: 'Edit this page on GitHub',
    },

    outline: 'deep',

    footer: {
      message: 'Released under the Apache-2.0 License.',
      copyright: '© onno-erp',
    },
  },
})
