package dev.wads.motoridecallconnect.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wads.motoridecallconnect.data.local.UserPreferences
import dev.wads.motoridecallconnect.ui.components.BigButton
import dev.wads.motoridecallconnect.ui.components.ButtonSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen(onComplete: () -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val userPreferences = remember { UserPreferences(context) }

    when (step) {
        0 -> WelcomeStep(onNext = { step = 1 })
        1 -> PermissionsStep(onNext = { step = 2 })
        2 -> UsageModeStep(onComplete = { mode ->
            CoroutineScope(Dispatchers.IO).launch {
                userPreferences.setUsageMode(mode)
                userPreferences.setOnboardingCompleted(true)
            }
            onComplete()
        })
    }
}

@Composable
fun WelcomeStep(onNext: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.height(300.dp).fillMaxWidth()) {
            // Placeholder for Image
            Box(modifier = Modifier.fillMaxSize().background(Color.DarkGray)) 
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Transparent, MaterialTheme.colorScheme.background)
                        )
                    )
            )
        }
        
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 24.dp)) {
                    Icon(imageVector = Icons.Default.Radio, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(text = "MotoTalk", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                }
                
                Text(
                    text = "Intercomunicador hands-free para motociclistas",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 24.dp)
                )
                
                FeatureItem(Icons.Default.Mic, "Comunicação por voz em tempo real, sem tirar as mãos do guidão")
                FeatureItem(Icons.Default.Radio, "Funciona via rede local / hotspot — sem depender de internet")
                FeatureItem(Icons.Default.Bluetooth, "Funciona com tela bloqueada e fone Bluetooth")
            }
            
            BigButton(text = "Começar", onClick = onNext, size = ButtonSize.Xl)
        }
    }
}

@Composable
fun PermissionsStep(onNext: () -> Unit) {
    val permissions = mutableListOf(
        Manifest.permission.RECORD_AUDIO
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_SCAN)
        }
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { _ ->
            // In a real app, handle rejections, show rationale, etc.
            onNext()
        }
    )

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Spacer(modifier = Modifier.height(48.dp))
            Text(text = "Permissões necessárias", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(text = "Para funcionar corretamente, o app precisa de:", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 32.dp))
            
            PermissionItem(Icons.Default.Mic, "Microfone", "Para captar sua voz (obrigatório)", true)
            Spacer(modifier = Modifier.height(16.dp))
            PermissionItem(Icons.Default.Notifications, "Notificações", "Para manter o app ativo em segundo plano")
            Spacer(modifier = Modifier.height(16.dp))
            PermissionItem(Icons.Default.Bluetooth, "Bluetooth", "Para conectar seu fone sem fio")
        }
        
        Column {
             Text(
                 text = "Você pode gerenciar permissões nas configurações do app a qualquer momento.",
                 style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant,
                 modifier = Modifier.padding(bottom = 16.dp),
                 textAlign = androidx.compose.ui.text.style.TextAlign.Center
             )
             BigButton(text = "Continuar", onClick = { launcher.launch(permissions.toTypedArray()) }, size = ButtonSize.Lg)
        }
    }
}

@Composable
fun UsageModeStep(onComplete: (String) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
    ) {
        Spacer(modifier = Modifier.height(48.dp))
        Text(text = "Como você vai usar?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(text = "Isso nos ajuda a personalizar a experiência.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 32.dp))

        UsageOption(
            icon = Icons.Default.Group,
            title = "Com garupa",
            description = "Conexão direta para conversar com quem está na mesma moto.",
            onClick = { onComplete("garupa") }
        )
        Spacer(modifier = Modifier.height(16.dp))
        UsageOption(
            icon = Icons.Default.TwoWheeler,
            title = "Com outra moto",
            description = "Comunicação moto-a-moto em comboio (alcance limitado pelo Wi-Fi).",
            onClick = { onComplete("piloto") }
        )
    }
}

@Composable
fun FeatureItem(icon: ImageVector, text: String) {
    Row(modifier = Modifier.padding(bottom = 16.dp)) {
        Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp).padding(top = 2.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Text(text = text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun PermissionItem(icon: ImageVector, title: String, description: String, required: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Row {
                Text(text = title, fontWeight = FontWeight.SemiBold)
                if (required) {
                    Text(text = "*", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 4.dp))
                }
            }
            Text(text = description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun UsageOption(icon: ImageVector, title: String, description: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .border(2.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
            .padding(24.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f), RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(text = title, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            Text(text = description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun WelcomeStepPreview() {
    dev.wads.motoridecallconnect.ui.theme.MotoRideCallConnectTheme {
        androidx.compose.material3.Surface {
             WelcomeStep(onNext = {})
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview(name = "Permissions Step")
@Composable
private fun PermissionsStepPreview() {
    dev.wads.motoridecallconnect.ui.theme.MotoRideCallConnectTheme {
        androidx.compose.material3.Surface {
             PermissionsStep(onNext = {})
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview(name = "Usage Mode Step")
@Composable
private fun UsageModeStepPreview() {
    dev.wads.motoridecallconnect.ui.theme.MotoRideCallConnectTheme {
        androidx.compose.material3.Surface {
             UsageModeStep(onComplete = {})
        }
    }
}
