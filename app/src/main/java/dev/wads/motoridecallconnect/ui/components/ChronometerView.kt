package dev.wads.motoridecallconnect.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

@Composable
fun ChronometerView(
    startTimeMillis: Long,
    modifier: Modifier = Modifier
) {
    var currentTime by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(key1 = startTimeMillis) {
        while (true) {
            currentTime = System.currentTimeMillis()
            delay(1000)
        }
    }

    val diff = currentTime - startTimeMillis
    val hours = TimeUnit.MILLISECONDS.toHours(diff)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(diff) % 60
    val seconds = TimeUnit.MILLISECONDS.toSeconds(diff) % 60

    val timeString = if (hours > 0) {
        String.format("%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }

    Text(
        text = timeString,
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
        letterSpacing = 2.sp,
        modifier = modifier
    )
}
