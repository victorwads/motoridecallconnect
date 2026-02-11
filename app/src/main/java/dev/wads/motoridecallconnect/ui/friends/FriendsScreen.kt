package dev.wads.motoridecallconnect.ui.friends

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wads.motoridecallconnect.R
import dev.wads.motoridecallconnect.data.model.Friend
import dev.wads.motoridecallconnect.ui.components.BadgeStatus
import dev.wads.motoridecallconnect.ui.components.BigButton
import dev.wads.motoridecallconnect.ui.components.ButtonVariant
import dev.wads.motoridecallconnect.ui.components.EmptyState
import dev.wads.motoridecallconnect.ui.components.StatusBadge

// Mock data similar to prototype
val mockFriends = listOf(
    Friend("1", "João Silva", "joaosilva", true),
    Friend("2", "Maria Oliveira", "mariamoto", false),
    Friend("3", "Carlos Souza", "carlostrip", true)
)

@Composable
fun FriendsScreen(
    onNavigateToProfile: (String) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    
    // Filter logic
    val filteredFriends = mockFriends.filter { 
        it.name.contains(searchQuery, ignoreCase = true) || 
        it.nickname.contains(searchQuery, ignoreCase = true)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .padding(top = 16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.friends_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            
            IconButton(
                onClick = { /* Add Friend Action */ },
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
            ) {
                Icon(
                    imageVector = Icons.Default.PersonAdd,
                    contentDescription = stringResource(R.string.add_friend_desc),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))

        // Search Bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text(stringResource(R.string.search_placeholder)) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
            )
        )
        
        Spacer(modifier = Modifier.height(16.dp))

        // List or Empty States
        if (filteredFriends.isEmpty() && searchQuery.isNotEmpty()) {
            EmptyState(
                icon = Icons.Default.Search,
                title = "Nenhum resultado",
                description = "Nenhum amigo encontrado para \"$searchQuery\"."
            )
        } else if (filteredFriends.isEmpty()) {
            EmptyState(
                icon = Icons.Default.Group,
                title = "Sem amigos ainda",
                description = "Adicione amigos para parear mais rápido e compartilhar histórico.",
                actionLabel = "Adicionar amigo",
                onActionClick = { /* Add friend */ }
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredFriends) { friend ->
                    FriendItem(friend = friend, onClick = { onNavigateToProfile(friend.id) })
                }
            }
        }
        
        // Bottom Button (if needed, present in prototype)
        Spacer(modifier = Modifier.weight(1f)) // Push to bottom if needed, or just remove if floating
        /* 
        Spacer(modifier = Modifier.height(16.dp))
        BigButton(
             text = "Adicionar amigo",
             icon = Icons.Default.PersonAdd,
             variant = ButtonVariant.Outline,
             onClick = {}
        )
        */
    }
}

@Composable
fun FriendItem(friend: Friend, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Avatar Placeholder
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = friend.nickname.take(1).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Column {
                Text(
                    text = friend.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                StatusBadge(
                    status = if (friend.online) BadgeStatus.Connected else BadgeStatus.Disconnected,
                    label = if (friend.online) "online" else "offline"
                )
            }
        }
        
        Icon(
            imageVector = Icons.Default.ChevronRight, 
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
