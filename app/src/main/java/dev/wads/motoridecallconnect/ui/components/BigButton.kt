package dev.wads.motoridecallconnect.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class ButtonVariant {
    Primary, Secondary, Destructive, Outline, Success, Warning
}

enum class ButtonSize {
    Default, Lg, Xl
}

@Composable
fun BigButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: ButtonVariant = ButtonVariant.Primary,
    size: ButtonSize = ButtonSize.Default,
    icon: ImageVector? = null,
    fullWidth: Boolean = true,
    disabled: Boolean = false
) {
    val height = when (size) {
        ButtonSize.Default -> 56.dp
        ButtonSize.Lg -> 64.dp
        ButtonSize.Xl -> 80.dp
    }
    
    val fontSize = when (size) {
        ButtonSize.Default -> 16.sp
        ButtonSize.Lg -> 18.sp
        ButtonSize.Xl -> 20.sp
    }

    val colors = when (variant) {
        ButtonVariant.Primary -> ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
        ButtonVariant.Secondary -> ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
        ButtonVariant.Destructive -> ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
            disabledContainerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.35f),
            disabledContentColor = MaterialTheme.colorScheme.onError.copy(alpha = 0.7f)
        )
        ButtonVariant.Success -> ButtonDefaults.buttonColors(
            containerColor = Color(0xFF1E8E3E),
            contentColor = Color(0xFFFFFFFF),
            disabledContainerColor = Color(0xFF1E8E3E).copy(alpha = 0.35f),
            disabledContentColor = Color(0xFFFFFFFF).copy(alpha = 0.7f)
        )
        ButtonVariant.Warning -> ButtonDefaults.buttonColors(
            containerColor = Color(0xFFF59E0B),
            contentColor = Color(0xFF1F1A09),
            disabledContainerColor = Color(0xFFF59E0B).copy(alpha = 0.35f),
            disabledContentColor = Color(0xFF1F1A09).copy(alpha = 0.7f)
        )
        ButtonVariant.Outline -> ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurface,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    val outlineBorder = BorderStroke(
        width = 1.dp,
        color = if (disabled) MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.outline
    )
    
    val buttonModifier = modifier
        .height(height)
        .let { if (fullWidth) it.fillMaxWidth() else it }

    if (variant == ButtonVariant.Outline) {
        OutlinedButton(
            onClick = onClick,
            modifier = buttonModifier,
            enabled = !disabled,
            shape = RoundedCornerShape(8.dp),
            colors = colors,
            border = outlineBorder
        ) {
            ButtonContent(icon, text, fontSize)
        }
    } else {
        Button(
            onClick = onClick,
            modifier = buttonModifier,
            enabled = !disabled,
            shape = RoundedCornerShape(8.dp),
            colors = colors
        ) {
            ButtonContent(icon, text, fontSize)
        }
    }
}

@Composable
private fun ButtonContent(icon: ImageVector?, text: String, fontSize: TextUnit) {
    if (icon != null) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(12.dp))
    }
    Text(text = text, fontSize = fontSize)
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun BigButtonPreview() {
    dev.wads.motoridecallconnect.ui.theme.MotoRideCallConnectTheme {
        androidx.compose.foundation.layout.Column(Modifier.padding(16.dp)) {
            BigButton(text = "Primary", onClick = {})
            Spacer(modifier = Modifier.height(8.dp))
            BigButton(text = "Secondary", onClick = {}, variant = ButtonVariant.Secondary)
            Spacer(modifier = Modifier.height(8.dp))
            BigButton(text = "Destructive", onClick = {}, variant = ButtonVariant.Destructive)
            Spacer(modifier = Modifier.height(8.dp))
            BigButton(text = "Outline", onClick = {}, variant = ButtonVariant.Outline)
        }
    }
}
