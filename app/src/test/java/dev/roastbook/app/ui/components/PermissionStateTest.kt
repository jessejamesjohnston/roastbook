package dev.roastbook.app.ui.components

import android.Manifest
import android.app.Application
import android.content.Context
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** A permission's standing as stored on this install, refreshed when the screen comes back from Settings. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class PermissionStateTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val camera = Manifest.permission.CAMERA
    private val app: Context get() = ApplicationProvider.getApplicationContext()
    private val activity get() = compose.activity

    private fun grant() = shadowOf(app as Application).grantPermissions(camera)
    private fun rationale(show: Boolean) = shadowOf(activity.packageManager).setShouldShowRequestPermissionRationale(camera, show)

    @Test fun neverAskedThenAskedThenAllowed() {
        assertEquals(PermissionAsk.FIRST_ASK, PermissionAsks.state(app, camera))
        PermissionAsks.markAsked(app, camera)
        assertEquals(PermissionAsk.ASK_AGAIN, PermissionAsks.state(app, camera))
        grant()
        assertEquals(PermissionAsk.GRANTED, PermissionAsks.state(app, camera))
    }

    @Test fun aSecondRefusalWithoutRationaleIsRecordedAsRefusedForGood() {
        val state = PermissionAskState(app, activity, camera)
        state.markAsked()
        rationale(true)
        state.answered(granted = false)
        assertEquals("Android will ask once more", PermissionAsk.ASK_AGAIN, state.value)
        rationale(false)
        state.answered(granted = false)
        assertEquals(PermissionAsk.BLOCKED, state.value)
    }

    @Test fun allowingInSettingsClearsARefusalForGood() {
        val state = PermissionAskState(app, activity, camera)
        state.markAsked()
        rationale(true); state.answered(granted = false)
        rationale(false); state.answered(granted = false)
        assertEquals(PermissionAsk.BLOCKED, state.value)
        grant()
        state.refresh() // coming back from Settings runs only this (ON_RESUME), no dialog answer
        assertEquals(PermissionAsk.GRANTED, state.value)
        assertEquals(false, app.getSharedPreferences("permission_asks", Context.MODE_PRIVATE).getBoolean("$camera.blocked", true))
        // Revoked later (in Settings, or Android's reset of an unused app's permissions): explain and
        // ask again rather than sending the person to Settings on a stale record.
        shadowOf(app as Application).denyPermissions(camera)
        state.refresh()
        assertEquals(PermissionAsk.ASK_AGAIN, state.value)
        // If Android still won't show its dialog, that answer (no rationale) is a refusal for good again.
        rationale(false); state.answered(granted = false)
        assertEquals(PermissionAsk.BLOCKED, state.value)
    }

    @Test fun withoutAnActivityADeniedAnswerIsNeverForGood() {
        val state = PermissionAskState(app, null, camera)
        state.markAsked()
        state.answered(granted = false)
        state.answered(granted = false)
        assertEquals(PermissionAsk.ASK_AGAIN, state.value)
    }

    @Test fun openAppSettingsGoesToThisAppsDetailsPage() {
        PermissionAsks.openAppSettings(activity)
        val intent = shadowOf(activity).nextStartedActivity
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
        assertEquals("package:${app.packageName}", intent.data.toString())
    }

    @Test fun theRememberedStateRefreshesWhenTheScreenResumes() {
        var ask: PermissionAskState? = null
        compose.setContent { ask = rememberPermissionAsk(camera) }
        compose.waitForIdle()
        assertEquals(PermissionAsk.FIRST_ASK, ask!!.value)

        grant() // the person allowed it in Settings and came back
        assertEquals("not until the screen resumes", PermissionAsk.FIRST_ASK, ask.value)
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        assertEquals(PermissionAsk.GRANTED, ask.value)
    }
}
