package dev.wads.motoridecallconnect.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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
        ButtonVariant.Primary -> ButtonDefaults.buttonColors()
        ButtonVariant.Secondary -> ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
        ButtonVariant.Destructive -> ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
        ButtonVariant.Success -> ButtonDefaults.buttonColors(containerColor = Color(0xFF22C55E))
        ButtonVariant.Warning -> ButtonDefaults.buttonColors(containerColor = Color(0xFFEAB308))
        ButtonVariant.Outline -> ButtonDefaults.outlinedButtonColors()
    }
    
    val buttonModifier = modifier
        .height(height)
        .let { if (fullWidth) it.fillMaxWidth() else it }

    if (variant == ButtonVariant.Outline) {
        OutlinedButton(
            onClick = onClick,
            modifier = buttonModifier,
            enabled = !disabled,
            shape = RoundedCornerShape(8.dp),
            colors = colors
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
