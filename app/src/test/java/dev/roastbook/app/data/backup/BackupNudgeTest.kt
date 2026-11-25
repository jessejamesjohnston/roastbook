package dev.roastbook.app.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackupNudgeTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 100 * day

    @Test fun emptyBookSaysNothing() {
        assertNull(BackupNudge.compute(BackupState(), 0, now))
    }

    @Test fun coffeesWithoutAFolderAskForOne() {
        assertEquals(BackupNudge.NoFolder, BackupNudge.compute(BackupState(), 1, now))
    }

    @Test fun folderButNoSuccessYetIsStale() {
        // Not "No backup in 3 days" for a folder chosen a minute ago.
        assertEquals(BackupNudge.NeverBackedUp, BackupNudge.compute(BackupState(folderUri = "content://x"), 2, now))
    }

    @Test fun recentBackupIsQuiet() {
        assertNull(BackupNudge.compute(BackupState(folderUri = "content://x", lastBackupAt = now - 2 * day), 2, now))
    }

    @Test fun oldBackupReportsItsAge() {
        assertEquals(BackupNudge.Stale(5), BackupNudge.compute(BackupState(folderUri = "content://x", lastBackupAt = now - 5 * day - 1), 2, now))
    }

    @Test fun backupsTurnedOffOutrankEveryOtherBanner() {
        val off = BackupState(off = BackupOff.FOLDER_GONE)
        assertEquals(BackupNudge.Off, BackupNudge.compute(off, 1, now))
        // A recent backup doesn't make an off switch safe: the banner still says backups are off.
        assertEquals(BackupNudge.Off, BackupNudge.compute(off.copy(lastBackupAt = now), 1, now))
        assertEquals(BackupNudge.Off, BackupNudge.compute(BackupState(off = BackupOff.FOLDER_REFUSED, lastBackupAt = now - 9 * day), 1, now))
    }

    @Test fun anEmptyBookSaysNothingEvenWithBackupsOff() {
        assertNull(BackupNudge.compute(BackupState(off = BackupOff.FOLDER_GONE), 0, now))
    }
}
