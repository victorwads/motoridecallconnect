package dev.wads.motoridecallconnect.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.wads.motoridecallconnect.data.model.UserProfile
import dev.wads.motoridecallconnect.data.repository.UserRepository

@Composable
fun UserProfileView(
    userId: String?,
    modifier: Modifier = Modifier,
    showId: Boolean = true,
    avatarSize: Int = 40
) {
    var profile by remember(userId) { mutableStateOf<UserProfile?>(null) }
    var isLoading by remember(userId) { mutableStateOf(userId != null) }

    LaunchedEffect(userId) {
        if (userId != null) {
            isLoading = true
            profile = UserRepository.getInstance().getUserProfile(userId)
            isLoading = false
        } else {
            profile = null
            isLoading = false
        }
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val painter = rememberVectorPainter(Icons.Default.Person)
        
        if (profile?.photoUrl != null) {
            AsyncImage(
                model = profile?.photoUrl,
                contentDescription = "Profile Picture",
                modifier = Modifier
                    .size(avatarSize.dp)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop,
                placeholder = painter,
                error = painter
            )
        } else {
            Icon(
                Icons.Default.Person,
                contentDescription = null,
                modifier = Modifier
                    .size(avatarSize.dp)
                    .padding((avatarSize / 10).dp)
            )
        }

        Spacer(modifier = Modifier.width((avatarSize / 4).dp))

        Column {
            val name = when {
                isLoading -> "Carregando..."
                profile != null -> profile?.displayName?.ifEmpty { "Sem Nome" } ?: "Sem Nome"
                userId == null -> "Ninguém"
                else -> "Usuário não encontrado"
            }
            
            Text(
                text = name,
                style = if (avatarSize < 30) MaterialTheme.typography.bodySmall else MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            
            if (showId && userId != null && avatarSize >= 30) {
                Text(
                    text = "ID: $userId",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
