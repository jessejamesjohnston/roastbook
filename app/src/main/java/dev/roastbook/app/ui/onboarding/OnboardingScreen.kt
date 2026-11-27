package dev.roastbook.app.ui.onboarding

import dev.roastbook.app.ui.components.focusRing
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.roastbook.app.R
import dev.roastbook.app.util.englishString
import dev.roastbook.app.data.prefs.UserPrefs
import javax.inject.Inject
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Records that the intro has been seen, so it shows only once; the write always lands. */
@HiltViewModel
class OnboardingViewModel @Inject constructor(private val prefs: UserPrefs) : ViewModel() {
    // Finishing navigates away in the same frame, which clears this ViewModel; the write must
    // land anyway or the intro comes back at the next launch.
    fun done() { viewModelScope.launch { withContext(NonCancellable) { prefs.setOnboardingDone(true) } } }
}

private data class Page(val icon: ImageVector, val title: Int, val body: Int)

/** Three pages, shown once on the first start. Strangers arrive here from the README, so it is not optional polish. */
@Composable
fun OnboardingScreen(
    onDone: () -> Unit,
    vm: OnboardingViewModel = hiltViewModel(),
    // A seam for tests, which run without Hilt's activity component.
    readerNotice: @Composable () -> Unit = { ReaderNotice() },
) {
    val pages = listOf(
        Page(Icons.Default.LocalCafe, R.string.onboarding_1_title, R.string.onboarding_1_body),
        Page(Icons.Default.PhotoCamera, R.string.onboarding_2_title, R.string.onboarding_2_body),
        Page(Icons.Default.NotificationsActive, R.string.onboarding_3_title, R.string.onboarding_3_body),
    )
    val pager = rememberPagerState { pages.size }
    val animationsOff = dev.roastbook.app.ui.theme.rememberAnimationsOff()
    val scope = rememberCoroutineScope()
    val finish = { vm.done(); onDone() }
    // One page back or forward, sliding unless animations are off.
    fun go(page: Int) {
        scope.launch { if (animationsOff) pager.scrollToPage(page) else pager.animateScrollToPage(page) }
    }
    val back = { if (pager.currentPage > 0) go(pager.currentPage - 1) }
    // Past the first page, the system Back and Escape step back a page instead of leaving.
    BackHandler(enabled = pager.currentPage > 0) { back() }

    Surface(
        Modifier.fillMaxSize().onPreviewKeyEvent { e ->
            if (e.type == KeyEventType.KeyDown && e.key == Key.Escape && pager.currentPage > 0) { back(); true } else false
        },
        color = MaterialTheme.colorScheme.surface,
    ) {
    // System bars include a desktop window's caption, so Skip never sits under its buttons.
    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
            TextButton(modifier = Modifier.focusRing(CircleShape), onClick = finish) { Text(stringResource(R.string.onboarding_skip)) }
        }
        HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { i ->
            val p = pages[i]
            // A readable measure on tablets; the page announces its position to TalkBack.
            val position = englishString(R.string.onboarding_page_position, i + 1, pages.size)
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                // Scrolls when large text or a short window can't fit the page.
                Modifier.widthIn(max = 560.dp).fillMaxWidth().verticalScroll(androidx.compose.foundation.rememberScrollState())
                    .padding(horizontal = 32.dp).semantics(mergeDescendants = true) { stateDescription = position },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    Modifier.size(120.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(p.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(56.dp))
                }
                Spacer(Modifier.height(32.dp))
                Text(stringResource(p.title), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(p.body),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                if (i == 1) readerNotice()
            }
            }
        }
        // Decorative for TalkBack (each page states its position); inactive dots use the outline
        // role so they clear 3:1 against the background.
        Row(Modifier.fillMaxWidth().clearAndSetSemantics { }, horizontalArrangement = Arrangement.Center) {
            repeat(pages.size) { i ->
                Box(
                    Modifier.padding(4.dp).size(if (pager.currentPage == i) 10.dp else 8.dp).clip(CircleShape)
                        .background(if (pager.currentPage == i) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        // Back (after the first page) beside Next, so a page read too fast is one tap away.
        Row(
            Modifier.align(Alignment.CenterHorizontally).widthIn(max = 480.dp).fillMaxWidth().padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            if (pager.currentPage > 0) {
                TextButton(onClick = back, modifier = Modifier.heightIn(min = 52.dp).focusRing(CircleShape)) { Text(stringResource(R.string.onboarding_back)) }
            }
            Button(
                onClick = { if (pager.currentPage >= pages.lastIndex) finish() else go(pager.currentPage + 1) },
                modifier = Modifier.weight(1f).heightIn(min = 52.dp).focusRing(CircleShape, onFill = true),
            ) { Text(stringResource(if (pager.currentPage < pages.lastIndex) R.string.onboarding_next else R.string.onboarding_start)) }
        }
        Spacer(Modifier.height(24.dp))
    } }
}
