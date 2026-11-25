package dev.roastbook.app.testutil

import dev.roastbook.app.data.db.CoffeeState
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class TestGraphTest {
    private val g = TestGraph()
    @After fun tearDown() = g.close()

    @Test fun anInsertedCoffeeIsReadBackThroughTheRepository() = runTest {
        g.insertCoffee("c1", CoffeeState.RATED) { copy(roaster = "Northwind") }
        assertEquals("Northwind", g.repo.get("c1")?.roaster)
    }
}
