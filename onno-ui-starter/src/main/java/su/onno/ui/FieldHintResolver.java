package su.onno.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-entity record-surface configuration — field hints, action placement, related lists, form
 * validations and detail widgets — authored on each {@link EntityView#fields}/{@link EntityView#detail}
 * and resolved by class and profile. The source of truth for field order/visibility/widget — what
 * used to live in the layout's section calls.
 *
 * <p>Profiles resolve the same way as {@link UiViewResolver} resolves lists: a profile that declares
 * its own view for the entity gets that view's configuration as a whole (no field-by-field merge),
 * every other profile gets the default view's ({@code profile()==null}). The single-argument
 * accessors return the default view's configuration.</p>
 *
 * <p>Kept separate from {@link UiViewResolver} on purpose: that resolver depends on
 * {@link ResolvedMetadataService}, which consumes these hints — folding them in
 * would create a cycle. This bean depends only on the views.</p>
 */
public class FieldHintResolver {

    private static final String DEFAULT = "";

    /** entity -> (profile id | "" for the default view) -> resolved configuration. */
    private final Map<Class<?>, Map<String, Config>> configs = new LinkedHashMap<>();

    private record Config(Map<String, FieldHint> hints, Map<String, String> actions,
                          List<RelatedList> relatedLists, List<FormValidation> validations,
                          List<DetailWidget> detailWidgets) {
    }

    private static final Config EMPTY = new Config(Map.of(), Map.of(), List.of(), List.of(), List.of());

    public FieldHintResolver(List<EntityView> views) {
        for (EntityView view : views) {
            if (view.entity() == null) {
                continue;
            }
            EntityConfigBuilder<Object> cfg = new EntityConfigBuilder<>();
            view.fields(cfg);
            DetailSpec<Object> detail = new DetailSpec<>();
            view.detail(detail);
            String profile = view.profile() == null ? DEFAULT : view.profile();
            configs.computeIfAbsent(view.entity(), k -> new LinkedHashMap<>())
                    .put(profile, new Config(cfg.buildFieldHints(), cfg.buildActions(),
                            cfg.buildRelatedLists(), cfg.buildValidations(), detail.build()));
        }
    }

    /** The profile's own view configuration if it declares one, else the default view's. */
    private Config config(Class<?> entity, String profileId) {
        Map<String, Config> byProfile = configs.get(entity);
        if (byProfile == null) {
            return EMPTY;
        }
        Config specific = profileId == null ? null : byProfile.get(profileId);
        if (specific != null) {
            return specific;
        }
        return byProfile.getOrDefault(DEFAULT, EMPTY);
    }

    /** Field hints for an entity's default view, or an empty map if it defines none. */
    public Map<String, FieldHint> forEntity(Class<?> entity) {
        return forEntity(entity, null);
    }

    /** Field hints for an entity as {@code profileId} sees it. */
    public Map<String, FieldHint> forEntity(Class<?> entity, String profileId) {
        return config(entity, profileId).hints();
    }

    /** Detail-header action placement overrides for an entity ({@code action -> primary|menu|hidden}). */
    public Map<String, String> actionsFor(Class<?> entity) {
        return actionsFor(entity, null);
    }

    public Map<String, String> actionsFor(Class<?> entity, String profileId) {
        return config(entity, profileId).actions();
    }

    /** Related-list panels authored on an entity's default view, or an empty list if none. */
    public List<RelatedList> relatedListsFor(Class<?> entity) {
        return relatedListsFor(entity, null);
    }

    public List<RelatedList> relatedListsFor(Class<?> entity, String profileId) {
        return config(entity, profileId).relatedLists();
    }

    /**
     * A single related-list panel by name, or {@code null} if no view of the entity declares one with
     * that name. The data endpoints that call this carry no profile, so the default view is searched
     * first, then the profile-specific ones — the junction's own read grant still gates the rows.
     */
    public RelatedList relatedList(Class<?> entity, String name) {
        return allConfigs(entity).stream()
                .flatMap(c -> c.relatedLists().stream())
                .filter(rl -> rl.name().equals(name))
                .findFirst()
                .orElse(null);
    }

    /** Live form validators authored on an entity's default view, in declaration order. */
    public List<FormValidation> validationsFor(Class<?> entity) {
        return validationsFor(entity, null);
    }

    public List<FormValidation> validationsFor(Class<?> entity, String profileId) {
        return config(entity, profileId).validations();
    }

    /** A validator by key from any view of the entity — default first (the validation feed has no profile). */
    public FormValidation validation(Class<?> entity, String key) {
        return allConfigs(entity).stream()
                .flatMap(c -> c.validations().stream())
                .filter(validation -> validation.key().equals(key))
                .findFirst()
                .orElse(null);
    }

    /** Record-surface custom widgets authored on an entity's default view, in display order. */
    public List<DetailWidget> detailWidgetsFor(Class<?> entity) {
        return detailWidgetsFor(entity, null);
    }

    public List<DetailWidget> detailWidgetsFor(Class<?> entity, String profileId) {
        return config(entity, profileId).detailWidgets();
    }

    /** Every view configuration of the entity, the default view's first. */
    private List<Config> allConfigs(Class<?> entity) {
        Map<String, Config> byProfile = configs.get(entity);
        if (byProfile == null) {
            return List.of();
        }
        List<Config> ordered = new ArrayList<>();
        Config def = byProfile.get(DEFAULT);
        if (def != null) {
            ordered.add(def);
        }
        byProfile.forEach((profile, config) -> {
            if (!DEFAULT.equals(profile)) {
                ordered.add(config);
            }
        });
        return ordered;
    }
}
