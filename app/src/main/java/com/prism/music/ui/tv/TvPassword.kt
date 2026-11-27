package com.prism.music.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.prism.music.R
import com.prism.music.tr

/**
 * A backup's password asked for on the TV (Settings → Backup): typed with the TV's own keyboard, which opens on the
 * field. [confirm]: a new password (backing up) is typed twice, as on the phone; opening a backup asks once.
 */
class TvPasswordAsk(val title: String, val note: String, val confirm: Boolean, val onDone: (CharArray) -> Unit)

/** Where a password typed on the TV stands after OK: typed once, ask again, done, or the two didn't match. */
enum class TvPasswordStep { AGAIN, DONE, MISMATCH, EMPTY }

/** What OK does with what's typed ([first], and [again] once it's asked for). */
fun tvPasswordStep(confirm: Boolean, first: String, again: String?): TvPasswordStep = when {
    first.isEmpty() -> TvPasswordStep.EMPTY
    !confirm -> TvPasswordStep.DONE
    again == null -> TvPasswordStep.AGAIN
    again == first -> TvPasswordStep.DONE
    else -> TvPasswordStep.MISMATCH
}

@Composable
fun TvPasswordPanel(ask: TvPasswordAsk, onCancel: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var first by remember(ask) { mutableStateOf("") }
    // Null until the password is asked for a second time.
    var again by remember(ask) { mutableStateOf<String?>(null) }
    var wrong by remember(ask) { mutableStateOf(false) }
    val field = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // The field takes focus and the keyboard opens at once, and again for the second time it's typed.
    LaunchedEffect(ask, again == null) { runCatching { field.requestFocus() }; keyboard?.show() }
    // Typing goes to the TV's keyboard: no ambient screen over it.
    HoldsAmbient()
    val submit: () -> Unit = {
        when (tvPasswordStep(ask.confirm, first, again)) {
            TvPasswordStep.EMPTY -> Unit
            TvPasswordStep.AGAIN -> { again = ""; wrong = false }
            TvPasswordStep.MISMATCH -> { again = ""; wrong = true }
            TvPasswordStep.DONE -> {
                val pw = first.toCharArray()
                first = ""; again = null
                keyboard?.hide()
                ask.onDone(pw)
            }
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
        Column(Modifier.width(620.dp).tvGlass(TvShapes.panel, TvPanelFill).padding(32.dp).testTag(TAG_PASSWORD)) {
            Text(ask.title, style = MaterialTheme.typography.headlineSmall, color = scheme.onSurface)
            Spacer(Modifier.height(10.dp))
            Text(ask.note, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant)
            Spacer(Modifier.height(20.dp))
            val typing = again ?: first
            androidx.compose.foundation.text.BasicTextField(
                value = typing,
                onValueChange = { v -> Idle.touched(); val t = v.take(128); if (again == null) first = t else again = t },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                textStyle = MaterialTheme.typography.titleMedium.copy(color = scheme.onSurface),
                cursorBrush = SolidColor(scheme.primary),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth().height(60.dp)
                    .background(TvGlassFill, RoundedCornerShape(30.dp))
                    .border(TV_RING_THIN, tvRing(), RoundedCornerShape(30.dp))
                    .focusRequester(field).testTag("$TAG_PASSWORD/field"),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxSize().padding(horizontal = 22.dp), contentAlignment = Alignment.CenterStart) {
                        if (typing.isEmpty()) Text(
                            tr(if (again == null) R.string.tv_backup_password_title else R.string.backup_password_again),
                            style = MaterialTheme.typography.titleMedium, color = scheme.onSurfaceVariant, maxLines = 1,
                        )
                        inner()
                    }
                },
            )
            if (wrong) {
                Spacer(Modifier.height(8.dp))
                Text(tr(R.string.backup_password_mismatch), style = MaterialTheme.typography.bodyMedium, color = scheme.error)
            }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TvButton(tr(R.string.done), submit, Modifier.tvItem("$TAG_PASSWORD/done"), primary = true)
                TvButton(tr(R.string.cancel), onCancel, Modifier.tvItem("$TAG_PASSWORD/cancel"))
            }
        }
    }
}

const val TAG_PASSWORD = "tv_password"
