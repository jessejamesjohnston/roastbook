package dev.roastbook.app.data.backup

/** What the History banner should say about backups, if anything. */
sealed class BackupNudge {
    /** The chosen folder stopped working and was forgotten: backups are off until a folder is chosen. */
    object Off : BackupNudge()
    /** Coffees exist but no folder was ever chosen. */
    object NoFolder : BackupNudge()
    /** A folder is chosen but no backup has finished yet (says "not backed up yet", not a day count). */
    object NeverBackedUp : BackupNudge()
    /** A folder exists but the newest successful backup is older than the threshold. */
    data class Stale(val days: Int) : BackupNudge()

    companion object {
        const val STALE_AFTER_DAYS = 3
        private const val DAY = 24L * 60 * 60 * 1000

        fun compute(state: BackupState, coffeeCount: Int, now: Long): BackupNudge? {
            if (coffeeCount == 0) return null
            if (state.off != null) return Off
            if (state.folderUri == null) return NoFolder
            val last = state.lastBackupAt ?: return NeverBackedUp
            val days = ((now - last) / DAY).toInt()
            return if (days >= STALE_AFTER_DAYS) Stale(days) else null
        }
    }
}
