package su.onno.ui;

import java.time.Instant;

/**
 * The last-known result of the update check, exposed to the web client via {@code /api/config}.
 * {@code updateAvailable} is true only when a latest version was successfully fetched and is strictly
 * newer than the running {@code currentVersion}.
 *
 * @param updateAvailable whether a newer version exists
 * @param currentVersion  the framework version this app is running ({@code ""} if unknown)
 * @param latestVersion   the latest version onno-cloud announced, or {@code null} if not yet known
 * @param releaseUrl      optional changelog / release-notes link for the latest version
 * @param checkedAt       when the last successful check completed, or {@code null} if none yet
 */
public record UpdateStatus(boolean updateAvailable, String currentVersion, String latestVersion,
                           String releaseUrl, Instant checkedAt) {

    /** The initial / cleared state: no update known for the given running version. */
    static UpdateStatus none(String currentVersion) {
        return new UpdateStatus(false, currentVersion, null, null, null);
    }
}
