package dev.roastbook.app.ui.detail

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import dev.roastbook.app.R
import dev.roastbook.app.data.db.CoffeeEntity
import dev.roastbook.app.ui.theme.RoastbookTheme
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule

/**
 * Shared setup for the Detail screen tests: a real [DetailViewModel] on a [DetailFixture], the
 * screen inside the app theme, and the activity-scoped [EditSessions] the screen uses.
 */
abstract class DetailScreenTestBase(key: String? = null, eligibleForReader: Boolean = false) {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    protected val f = DetailFixture(key, eligibleForReader)
    protected lateinit var vm: DetailViewModel
    /** How many coroutines the ViewModel keeps running for its flows once the page has loaded. */
    private var flowJobs = 0
    protected lateinit var sessions: EditSessions
    protected var backs = 0
    /** Set false to take the page out of the composition, as navigating away does. */
    protected var shown by mutableStateOf(true)

    protected fun str(id: Int, vararg args: Any): String = f.context.getString(id, *args)

    @After fun closeFixture() = f.close()

    protected fun insert(id: String = "c1", build: CoffeeEntity.() -> CoffeeEntity = { this }) =
        runBlocking { f.graph.insertCoffee(id, build = build) }

    protected fun row(id: String = "c1"): CoffeeEntity? = runBlocking { f.graph.dao.get(id) }

    /**
     * Shows coffee [id] and waits for its row to load ([awaitRow] false shows it without waiting, for
     * a row that does not exist yet). [wrap] can add composition locals around the page.
     */
    protected fun show(
        id: String = "c1",
        inPane: Boolean = false,
        editRating: Boolean = false,
        awaitRow: Boolean = true,
        before: (EditSessions) -> Unit = {},
        wrap: @Composable (@Composable () -> Unit) -> Unit = { it() },
    ) {
        vm = f.vm(id)
        var ready by mutableStateOf(false)
        compose.setContent {
            sessions = rememberEditSessions()
            RoastbookTheme {
                if (ready && shown) wrap { DetailScreen(id = id, editRating = editRating, onBack = { backs++ }, inPane = inPane, vm = vm) }
            }
        }
        compose.waitForIdle()
        before(sessions)
        ready = true
        if (!awaitRow) { compose.waitForIdle(); return }
        compose.awaitUntil { vm.coffee.value != null }
        flowJobs = vmJobs()
    }

    private fun vmJobs() = vm.viewModelScope.coroutineContext.job.children.count { it.isActive }

    /**
     * Waits until every write the page asked its ViewModel for has landed: each one is a coroutine
     * of the ViewModel's scope, which is back to just its flows once they have. A test that checks
     * nothing was written waits here instead of for a fixed time.
     */
    protected fun awaitWritesDone() = compose.awaitUntil { vmJobs() <= flowJobs }

    /** Sets one rating row (by its label) the way TalkBack's adjust action does. */
    protected fun rateRow(label: Int, value: Int) {
        // Profile rows add their range ("Body, Light to Full"), so the label is matched as a prefix.
        compose.onNode(hasContentDescription(str(label), substring = true) and SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(value.toFloat()) }
    }

    protected fun rateAll(aroma: Int, flavor: Int, finish: Int) {
        rateRow(R.string.sort_aroma, aroma)
        rateRow(R.string.sort_flavor, flavor)
        rateRow(R.string.sort_finish, finish)
    }

    protected fun SemanticsNodeInteraction.exists(): Boolean = runCatching { assertExists() }.isSuccess
}
