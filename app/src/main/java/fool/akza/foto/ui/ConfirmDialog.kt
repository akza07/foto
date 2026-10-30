package fool.akza.foto.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fool.akza.foto.R
import fool.akza.foto.ui.theme.AmoledBlack
import fool.akza.foto.ui.theme.Border
import fool.akza.foto.ui.theme.DeleteRed
import fool.akza.foto.ui.theme.Surface as ThemeSurface
import fool.akza.foto.ui.theme.TextMuted
import fool.akza.foto.ui.theme.TextPrimary

/**
 * Shared shadcn-style confirmation modal. Used for delete (single and batch) and
 * clear-cache, which previously had three near-identical implementations.
 * When [destructive] the confirm button is red; otherwise it is the primary
 * (light) fill.
 */
@Composable
internal fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    destructive: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Bloom in with the same scale+fade language as the wheel, so a confirm
    // following a radial action reads as a continuation rather than a pop-up.
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    AnimatedVisibility(
        visible = entered,
        enter = fadeIn(tween(180)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(AmoledBlack.copy(alpha = 0.7f))
                .clickable(onClick = onDismiss, indication = null, interactionSource = null),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedVisibility(
                visible = entered,
                enter = scaleIn(
                    initialScale = 0.92f,
                    animationSpec = tween(240, easing = FastOutSlowInEasing),
                ) + fadeIn(tween(180)),
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = ThemeSurface,
                    border = BorderStroke(1.dp, Border),
                    shadowElevation = 0.dp,
                ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = title,
                    color = TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = message,
                    color = TextMuted,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )
                Spacer(Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Cancel (outlined).
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(
                                role = Role.Button,
                                onClick = onDismiss,
                                indication = null,
                                interactionSource = null,
                            ),
                        shape = RoundedCornerShape(10.dp),
                        color = Color.Transparent,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.action_cancel),
                                color = TextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                    // Confirm.
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(
                                role = Role.Button,
                                onClick = onConfirm,
                                indication = null,
                                interactionSource = null,
                            ),
                        shape = RoundedCornerShape(10.dp),
                        color = if (destructive) DeleteRed else TextPrimary,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = confirmLabel,
                                color = if (destructive) Color.White else AmoledBlack,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        } // Confirm button Box
                    } // Confirm button Surface
                } // Button Row
            } // Content Column
                } // Card Surface
            } // Card scale-in
        } // Scrim Box
    } // Outer fade-in
}
