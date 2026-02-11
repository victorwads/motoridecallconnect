package dev.wads.motoridecallconnect.ui.friends

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.wads.motoridecallconnect.R
import dev.wads.motoridecallconnect.ui.components.BadgeStatus
import dev.wads.motoridecallconnect.ui.components.BigButton
import dev.wads.motoridecallconnect.ui.components.ButtonVariant
import dev.wads.motoridecallconnect.ui.components.StatusBadge
import dev.wads.motoridecallconnect.ui.components.StatusCard

@Composable
fun FriendProfileScreen(
    friendId: String,
    onNavigateBack: () -> Unit,
    onInvitePair: () -> Unit
) {
    // Find friend from mock data
    val friend = mockFriends.find { it.id == friendId }

    if (friend == null) {
        Column(modifier = Modifier.padding(16.dp)) {
            TextButton(onClick = onNavigateBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.back))
            }
            Text(stringResource(R.string.friend_not_found))
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .padding(top = 16.dp)
    ) {
        // Back button
        TextButton(
            onClick = onNavigateBack,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary)
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.friends_title))
        }
        
        Spacer(modifier = Modifier.height(24.dp))

        StatusCard {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Large Avatar
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = friend.nickname.take(1).uppercase(),
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(
                    text = friend.name,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                
                Text(
                    text = "@${friend.nickname.lowercase()}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                StatusBadge(
                    status = if (friend.online) BadgeStatus.Connected else BadgeStatus.Disconnected,
                    label = if (friend.online) "online" else "offline"
                )
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // Actions
                BigButton(
                    text = "Convidar para parear",
                    icon = Icons.Default.Link,
                    variant = ButtonVariant.Primary,
                    onClick = onInvitePair,
                    fullWidth = true
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                BigButton(
                    text = "Criar sala com ${friend.nickname}",
                    icon = Icons.Default.Group,
                    variant = ButtonVariant.Outline,
                    onClick = { /* Create Room */ },
                    fullWidth = true
                )
            }
        }
    }
}
