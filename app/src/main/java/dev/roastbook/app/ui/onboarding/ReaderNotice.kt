package dev.roastbook.app.ui.onboarding

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.roastbook.app.R
import dev.roastbook.app.extraction.NanoTier
import dev.roastbook.app.reader.LocalReaderManager
import dev.roastbook.app.reader.ReaderOffer
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Decides, once when created, whether onboarding mentions the on-device reader or the cloud key:
 * [offer] stays null while checking and when the phone's own AI can read bags.
 */
@HiltViewModel
class ReaderNoticeViewModel @Inject constructor(private val nano: NanoTier, private val manager: LocalReaderManager) : ViewModel() {
    /** Null while checking or when the phone's own AI is there (nothing to say). */
    val offer = MutableStateFlow<ReaderOffer?>(null)
    val modelSize: Long = manager.spec.sizeBytes

    init {
        viewModelScope.launch {
            val status = ReaderOffer.probe(nano)
            // Onboarding can only see "unavailable"; a Nano that reports available and then fails is
            // caught later, and Settings offers the reader then.
            offer.value = ReaderOffer.of(manager.eligibility, status, nanoFailing = false, hasReaderFiles = false).takeIf { it != ReaderOffer.HIDDEN }
        }
    }
}

/** One plain sentence on the "we read the bag" page when this phone has no built-in AI for it. */
@Composable
fun ReaderNotice(vm: ReaderNoticeViewModel = hiltViewModel()) {
    val offer by vm.offer.collectAsState()
    val o = offer ?: return
    val context = LocalContext.current
    val text = when (o) {
        ReaderOffer.OFFER -> stringResource(R.string.onboarding_reader_download, Formatter.formatShortFileSize(context, vm.modelSize))
        else -> stringResource(R.string.onboarding_reader_cloud)
    }
    Spacer(Modifier.height(20.dp))
    Text(
        text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(16.dp),
    )
}
