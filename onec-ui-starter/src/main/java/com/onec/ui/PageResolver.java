package com.onec.ui;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves an authored {@link Page} for a route, scoped to the active profile and
 * the client's {@link Viewport}: the most specific match wins — a page for this
 * (profile, viewport) beats one for the profile alone, which beats the universal
 * default; else none (the caller falls back). The page-level peer of
 * {@link UiViewResolver}.
 */
public class PageResolver {

    private static final String DEFAULT = "";

    // route -> profile id ("" = default) -> viewport (null = any) -> page
    private final Map<String, Map<String, Map<Viewport, Page>>> pages = new LinkedHashMap<>();

    public PageResolver(List<Page> pages) {
        for (Page page : pages) {
            if (page.route() == null) {
                continue;
            }
            String profile = page.profile() == null ? DEFAULT : page.profile();
            this.pages
                    .computeIfAbsent(page.route(), k -> new LinkedHashMap<>())
                    .computeIfAbsent(profile, k -> new LinkedHashMap<>())
                    .put(page.viewport(), page);
        }
    }

    /** The page for this route under this profile and viewport, or {@code null}. */
    public Page resolve(String route, String profileId, Viewport viewport) {
        Map<String, Map<Viewport, Page>> byProfile = pages.get(route);
        if (byProfile == null) {
            return null;
        }
        Page page = pick(byProfile.get(profileId), viewport);
        return page != null ? page : pick(byProfile.get(DEFAULT), viewport);
    }

    /** Within a profile bucket, a viewport-specific page wins over the universal one. */
    private static Page pick(Map<Viewport, Page> byViewport, Viewport viewport) {
        if (byViewport == null) {
            return null;
        }
        Page specific = byViewport.get(viewport);
        return specific != null ? specific : byViewport.get(null);
    }
}
