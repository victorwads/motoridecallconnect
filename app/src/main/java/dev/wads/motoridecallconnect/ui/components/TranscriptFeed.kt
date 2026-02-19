package dev.wads.motoridecallconnect.ui.components

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.google.firebase.auth.FirebaseAuth
import dev.wads.motoridecallconnect.R
import dev.wads.motoridecallconnect.data.model.TranscriptStatus
import dev.wads.motoridecallconnect.data.repository.UserRepository

import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.IconButton

data class TranscriptFeedItem(
    val id: String,
    val authorId: String?,
    val authorName: String,
    val text: String,
    val timestampMs: Long,
    val status: TranscriptStatus,
    val errorMessage: String? = null
)

@Composable
fun TranscriptFeed(
    items: List<TranscriptFeedItem>,
    emptyText: String,
    maxItems: Int = 50,
    onPlayAudio: ((String) -> Unit)? = null,
    onRetry: ((String) -> Unit)? = null
) {
    if (items.isEmpty()) {
        Text(
            text = emptyText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }

    val visibleItems = if (items.size > maxItems) items.takeLast(maxItems) else items
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        visibleItems.forEach { item ->
            TranscriptFeedLine(
                item = item,
                onPlayAudio = onPlayAudio,
                onRetry = onRetry
            )
        }
    }
}

@Composable
private fun TranscriptFeedLine(
    item: TranscriptFeedItem,
    onPlayAudio: ((String) -> Unit)? = null,
    onRetry: ((String) -> Unit)? = null
) {
    val localUser = FirebaseAuth.getInstance().currentUser
    val localUid = localUser?.uid
    val isLocal = !localUid.isNullOrBlank() && localUid == item.authorId
    val photoOverride = if (!localUid.isNullOrBlank() && localUid == item.authorId) {
        localUser.photoUrl?.toString()
    } else {
        null
    }
    val timeLabel = remember(item.timestampMs) {
        DateFormat.format("HH:mm:ss", item.timestampMs).toString()
    }
    val lineColor = when (item.status) {
        TranscriptStatus.ERROR -> MaterialTheme.colorScheme.error
        TranscriptStatus.PROCESSING -> MaterialTheme.colorScheme.onSurfaceVariant
        TranscriptStatus.SUCCESS -> MaterialTheme.colorScheme.onSurface
    }
    val displayText = when {
        item.text.isNotBlank() -> item.text
        item.status == TranscriptStatus.PROCESSING -> stringResource(R.string.transcript_status_processing_short)
        item.status == TranscriptStatus.ERROR -> stringResource(R.string.transcript_status_error_short)
        else -> ""
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Transparent)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        // ... (existing content logic needs to be careful here)
        // I'll rewrite the Row content
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
           // Avatar
           Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (photoOverride != null) {
                    AsyncImage(
                        model = photoOverride,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        modifier = Modifier.padding(4.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            Column {
                 Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isLocal) "You" else item.authorName,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = timeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                Text(
                    text = displayText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = lineColor
                )
            }
        }
        
        // Actions
        if (isLocal) {
            Row(
                 horizontalArrangement = Arrangement.End,
                 verticalAlignment = Alignment.CenterVertically
            ) {
                // Play button
                if (item.status == TranscriptStatus.SUCCESS || item.status == TranscriptStatus.ERROR) {
                     IconButton(
                        onClick = { onPlayAudio?.invoke(item.id) },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Play",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                
                // Retry button
                if (item.status == TranscriptStatus.ERROR) {
                    IconButton(
                        onClick = { onRetry?.invoke(item.id) },
                        modifier = Modifier.size(24.dp)
                    ) {
                         Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Retry",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        TranscriptSpeakerAvatar(
            userId = item.authorId,
            name = item.authorName,
            photoUrlOverride = photoOverride
        )

        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = displayText,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = lineColor
                )
                when (item.status) {
                    TranscriptStatus.PROCESSING -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 2.dp
                        )
                    }
                    TranscriptStatus.ERROR -> {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                    TranscriptStatus.SUCCESS -> Unit
                }
                Text(
                    text = timeLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (item.status == TranscriptStatus.ERROR && !item.errorMessage.isNullOrBlank()) {
                Text(
                    text = item.errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        
        // Actions
        if (isLocal) {
            Row(
                 horizontalArrangement = Arrangement.End,
                 verticalAlignment = Alignment.CenterVertically
            ) {
                // Play button
                if (item.status == TranscriptStatus.SUCCESS || item.status == TranscriptStatus.ERROR) {
                     IconButton(
                        onClick = { onPlayAudio?.invoke(item.id) },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Play",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                
                // Retry button
                if (item.status == TranscriptStatus.ERROR) {
                    IconButton(
                        onClick = { onRetry?.invoke(item.id) },
                        modifier = Modifier.size(24.dp)
                    ) {
                         Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Retry",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TranscriptSpeakerAvatar(
    userId: String?,
    name: String,
    photoUrlOverride: String? = null
) {
    val size = 28.dp
    val placeholderPainter = rememberVectorPainter(Icons.Default.Person)
    var resolvedPhotoUrl by remember(userId, photoUrlOverride) { mutableStateOf(photoUrlOverride) }

    LaunchedEffect(userId, photoUrlOverride) {
        if (!photoUrlOverride.isNullOrBlank()) {
            resolvedPhotoUrl = photoUrlOverride
            return@LaunchedEffect
        }
        resolvedPhotoUrl = if (userId.isNullOrBlank()) {
            null
        } else {
            runCatching { UserRepository.getInstance().getUserProfile(userId)?.photoUrl }.getOrNull()
        }
    }

    Box(
        modifier = Modifier
            .size(size)
            .border(width = 1.dp, color = MaterialTheme.colorScheme.outlineVariant, shape = CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (!resolvedPhotoUrl.isNullOrBlank()) {
            AsyncImage(
                model = resolvedPhotoUrl,
                contentDescription = name,
                modifier = Modifier
                    .size(size - 4.dp)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop,
                placeholder = placeholderPainter,
                error = placeholderPainter
            )
        } else {
            Text(
                text = name.trim().take(1).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
