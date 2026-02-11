package dev.wads.motoridecallconnect.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class BadgeStatus {
    Connected, Disconnected, Connecting, Error, Excellent, Good, Poor
}

@Composable
fun StatusBadge(
    status: BadgeStatus,
    label: String? = null,
    modifier: Modifier = Modifier
) {
    val config = when (status) {
        BadgeStatus.Connected -> BadgeConfig(Color(0xFF22C55E), "Conectado", false)
        BadgeStatus.Disconnected -> BadgeConfig(Color.Gray, "Não conectado", false)
        BadgeStatus.Connecting -> BadgeConfig(Color(0xFFEAB308), "Conectando…", true)
        BadgeStatus.Error -> BadgeConfig(Color(0xFFEF4444), "Erro", true)
        BadgeStatus.Excellent -> BadgeConfig(Color(0xFF22C55E), "Excelente", false)
        BadgeStatus.Good -> BadgeConfig(Color(0xFFEAB308), "Boa", false)
        BadgeStatus.Poor -> BadgeConfig(Color(0xFFEF4444), "Ruim", false)
    }

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .background(config.color, CircleShape)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label ?: config.label,
            fontSize = 14.sp,
            color = Color.Unspecified
        )
    }
}

private data class BadgeConfig(val color: Color, val label: String, val pulse: Boolean)

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun StatusBadgePreview() {
    dev.wads.motoridecallconnect.ui.theme.MotoRideCallConnectTheme {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
        ) {
            StatusBadge(status = BadgeStatus.Connected)
            StatusBadge(status = BadgeStatus.Disconnected)
            StatusBadge(status = BadgeStatus.Connecting)
            StatusBadge(status = BadgeStatus.Error)
            StatusBadge(status = BadgeStatus.Excellent)
        }
    }
}
