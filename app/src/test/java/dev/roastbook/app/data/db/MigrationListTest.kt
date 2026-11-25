package dev.roastbook.app.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** The hand-written migrations every open of the database (live, staged restore) is given. */
class MigrationListTest {
    @Test fun theOnlyHandWrittenStepIsTwoToThree() {
        val step = RoastbookDatabase.MIGRATIONS.single()
        assertSame(RoastbookDatabase.MIGRATION_2_3, step)
        assertEquals(2, step.startVersion)
        assertEquals(3, step.endVersion)
    }
}
