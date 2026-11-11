package com.minim.fermata.backup

/** What a file in the backup folder is. */
enum class BackupRole {
    /** The newest good backup of one phone: `minim-<device>-latest.minim`. */
    LATEST,

    /** The one before it, kept in case `latest` turns out bad: `minim-<device>-previous.minim`. */
    PREVIOUS,

    /** The older one-file-per-day name, `minim-backup-<date>.minim`. Read, never written. */
    DAILY,
}

/** A backup file name, taken apart. [deviceId] is null for [BackupRole.DAILY] names (read it from the manifest). */
data class BackupName(val deviceId: String?, val role: BackupRole)

/**
 * File names in the backup folder. Each phone writes only its own files, named by its device id,
 * so two phones sharing a synced folder never write the same file:
 *
 * - `minim-<device>-latest.minim` and `minim-<device>-previous.minim`: one pair per phone
 * - `minim-<device>-<nonce>.tmp`: a backup being written. Readers ignore it (wrong extension).
 */
object BackupNames {
    private const val PREFIX = "minim-"
    private const val LATEST_SUFFIX = "-latest" + BackupArchive.EXTENSION
    private const val PREVIOUS_SUFFIX = "-previous" + BackupArchive.EXTENSION
    const val TEMP_EXTENSION = ".tmp"

    private val deviceIdPattern = Regex("[A-Za-z0-9_-]{1,64}")

    /** Device ids end up in file names, so they are limited to letters, digits, `_` and `-`. */
    fun isValidDeviceId(deviceId: String): Boolean =
        deviceIdPattern.matches(deviceId) && deviceId != com.minim.fermata.log.EventId.LEGACY_DEVICE

    fun latest(deviceId: String): String = PREFIX + checked(deviceId) + LATEST_SUFFIX

    fun previous(deviceId: String): String = PREFIX + checked(deviceId) + PREVIOUS_SUFFIX

    fun temp(deviceId: String, nonce: String): String = PREFIX + checked(deviceId) + "-" + nonce + TEMP_EXTENSION

    /** True for a temp file this phone wrote (left behind when a backup was killed half way). */
    fun isOwnTemp(name: String, deviceId: String): Boolean =
        name.startsWith(PREFIX + deviceId + "-") && name.endsWith(TEMP_EXTENSION)

    /** Takes a backup file name apart, or returns null for anything else (temp files included). */
    fun parse(name: String): BackupName? {
        if (!name.startsWith(PREFIX) || !name.endsWith(BackupArchive.EXTENSION)) return null
        val role = when {
            name.endsWith(LATEST_SUFFIX) -> BackupRole.LATEST
            name.endsWith(PREVIOUS_SUFFIX) -> BackupRole.PREVIOUS
            BackupArchive.isBackupName(name) -> return BackupName(null, BackupRole.DAILY)
            else -> return null
        }
        val suffix = if (role == BackupRole.LATEST) LATEST_SUFFIX else PREVIOUS_SUFFIX
        val device = name.substring(PREFIX.length, name.length - suffix.length)
        return if (isValidDeviceId(device)) BackupName(device, role) else null
    }

    private fun checked(deviceId: String): String {
        require(isValidDeviceId(deviceId)) { "device id can't be used in a file name: $deviceId" }
        return deviceId
    }
}
