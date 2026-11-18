import { createContext } from "react";

/**
 * True inside a `<Page>`, which already gives its content the page gutter. A full-height (`fill`)
 * list rendered straight as a route's content has no such parent, so it takes the gutter itself,
 * the same padding a default list page gets; inside a `Page` it leaves the padding to the page.
 */
export const PageGutterContext = createContext(false);
