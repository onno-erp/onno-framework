package su.onno.ui.gradle

/**
 * The minimal app `onnoUiInit` seeds into `src/main/ui`: a Vite app that renders the framework's
 * defaults, with the conventional layout to grow from.
 */
internal object UiTemplate {

    fun files(version: String): List<Pair<String, String>> = listOf(
        "package.json" to """
            {
              "private": true,
              "type": "module",
              "scripts": {
                "dev": "vite",
                "build": "vite build",
                "typecheck": "tsc --noEmit"
              },
              "dependencies": {
                "@onno/ui": "$version",
                "react": "^19.2.7",
                "react-dom": "^19.2.7"
              },
              "devDependencies": {
                "@types/react": "^19.2.17",
                "typescript": "~5.6.2",
                "vite": "^6.4.5"
              }
            }
        """,
        "vite.config.ts" to """
            import { defineConfig } from "vite";
            import { onno } from "@onno/ui/vite";

            // `npm run dev`: hot reload against the backend on :8080 (`onno({ backend })` to change it).
            export default defineConfig({ plugins: [onno()] });
        """,
        "tsconfig.json" to """
            {
              "compilerOptions": {
                "target": "ES2020",
                "lib": ["ES2020", "DOM", "DOM.Iterable"],
                "module": "ESNext",
                "moduleResolution": "bundler",
                "jsx": "react-jsx",
                "strict": true,
                "skipLibCheck": true,
                "isolatedModules": true,
                "noEmit": true,
                "types": ["vite/client"]
              },
              "include": ["**/*.ts", "**/*.tsx"],
              "exclude": ["node_modules", "dist"]
            }
        """,
        "index.html" to """
            <!doctype html>
            <html lang="en">
              <head>
                <meta charset="UTF-8" />
                <meta name="viewport" content="width=device-width, initial-scale=1.0" />
                <link rel="icon" type="image/svg+xml" href="/favicon.svg" />
                <title>onno</title>
              </head>
              <body>
                <div id="root"></div>
                <script type="module" src="./main.tsx"></script>
              </body>
            </html>
        """,
        ".gitignore" to """
            node_modules/
            dist/
            model.gen.ts
        """,
        "main.tsx" to """
            /**
             * The app's UI. The server exposes the business model and a default screen for every
             * entity; declare here only what differs. Entity names, fields and enum values are typed
             * from the Java model (model.gen.ts, regenerated on every build).
             *
             *   pages/       TSX list, record and ordinary pages
             *   components/  your own components
             */
            import { defineApp } from "@onno/ui";
            import { HomePage } from "./pages/HomePage";
            // import { ChartColumn, ShoppingCart } from "@onno/ui/icons";

            defineApp({
              pages: [
                { path: "/", component: HomePage },
                // Import your TSX pages, then register entity screens by logical name:
                // { list: "Orders", component: OrdersPage },
                // { record: "Orders", component: OrderPage },
                // { path: "/reports", component: ReportsPage, label: "Reports", icon: ChartColumn, roles: ["MANAGER"] },
              ],
              // The sidebar groups every readable entity by its `context` and lists labelled pages.
              // Curate it instead with sections of entity names and page paths:
              // navigation: [{ title: "Sales", icon: ShoppingCart, items: ["Orders", "/reports"] }],
            });
        """,
        "pages/HomePage.tsx" to """
            import { DefaultHome } from "@onno/ui";

            /**
             * The home page. It starts as the framework's default (entity cards grouped by context);
             * replace it with your own, e.g.
             *
             *   <Page title="Overview">
             *     <Stat title="Open orders" measure={count("Orders", { open: true })} />
             *     <EntityList entity="Orders" where={{ open: true }} />
             *   </Page>
             *
             * and read data with useRecords("Orders", { where: { open: true } }) or useQuery.
             */
            export function HomePage() {
              return <DefaultHome />;
            }
        """,
        "pages/README.md" to """
            Register React pages in main.tsx with defineApp({ pages: [...] }).
            Use { list: "Orders", component: OrdersPage } and { record: "Orders", component: OrderPage }
            for model-resolved entity screens, or { path: "/ops", component: OpsPage } for an app page.
            Import icon components from @onno/ui/icons and pass icon: ClipboardList.

            ```tsx
            import { EntityList, RecordPage, RecordFields, type ListPageProps, type RecordPageProps } from "@onno/ui";

            export function OrdersPage({ list }: ListPageProps<"Orders">) {
              return <EntityList list={list} columns={["number", "date", "customer", "total"]} sort="-date" />;
            }

            export function OrderPage({ form }: RecordPageProps<"Orders">) {
              return <RecordPage form={form}>
                <RecordFields form={form} fields={{ customer: { width: "half" }, note: { input: "textarea" } }} />
              </RecordPage>;
            }
            ```

            RecordLines renders a tabular section. Custom React controls read form.values and call
            form.set(field, value), sharing the standard save/post, validation and dirty guard.
            RecordPage without children uses the full default layout, including all sections.
        """,
    ).map { (path, content) -> path to content.trimIndent() + "\n" }
}
