package su.onno.access;

/**
 * The capabilities the core framework and the web starter enforce. Grant them to roles in
 * {@link RoleGrants}:
 *
 * <pre>{@code
 * .role(AppRole.ACCOUNTANT, r -> r.permit(CorePermissions.MANAGE_PERIOD_CLOSE))
 * }</pre>
 *
 * <p>In an application without grants ({@link RoleGrants#open()}) every signed-in user holds every
 * permission except {@link #OVERRIDE_PERIOD_CLOSE} and {@link #ADMINISTER_PROCESSES}, which are only
 * ever held through an explicit grant ({@link EffectiveAccess#granted(Permission)}).
 */
public final class CorePermissions {

    /** Read and change {@code @Constant} settings (the settings screen and its endpoint). */
    public static final Permission EDIT_SETTINGS =
            Permission.of("settings.edit", "Read and change @Constant settings");

    /** Delete any comment, not only one's own. */
    public static final Permission MODERATE_COMMENTS =
            Permission.of("comments.moderate", "Delete any comment, not only one's own");

    /** Close and reopen accounting periods. */
    public static final Permission MANAGE_PERIOD_CLOSE =
            Permission.of("periodClose.manage", "Close and reopen accounting periods");

    /**
     * Change documents inside a closed period without reopening it (audited). Never implied by an
     * application without grants: it must be granted explicitly.
     */
    public static final Permission OVERRIDE_PERIOD_CLOSE =
            Permission.of("periodClose.override", "Change documents inside a closed period (audited)");

    /**
     * See, claim, complete, delegate and cancel every process task and instance. Never implied by an
     * application without grants: it must be granted explicitly.
     */
    public static final Permission ADMINISTER_PROCESSES =
            Permission.of("processes.administer", "Operate every process task and instance");

    private CorePermissions() {
    }
}
