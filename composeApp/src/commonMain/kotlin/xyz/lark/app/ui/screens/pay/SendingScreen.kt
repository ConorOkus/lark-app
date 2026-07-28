package xyz.lark.app.ui.screens.pay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import xyz.lark.app.ui.components.GoldSpinner
import xyz.lark.app.ui.theme.LarkColors
import xyz.lark.app.ui.theme.LarkTheme

private val SendingGap = 26.dp
private val SpinnerSize = 56.dp

private const val SENDING_ALPHA = 0.6f

/**
 * Pay — sending (spec block `data-screen-label="Pay — sending"`): the 56dp gold
 * ring spinner over a dimmed "Sending" wordmark, centered on the background.
 */
@Composable
fun SendingScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(SendingGap, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        GoldSpinner(size = SpinnerSize)
        Text(
            text = "Sending",
            style = LarkTheme.typography.screenTitle.copy(
                fontSize = 24.sp,
                lineHeight = 24.sp,
                letterSpacing = (-0.02).em,
            ),
            color = LarkColors.TextPrimary.copy(alpha = SENDING_ALPHA),
        )
    }
}
