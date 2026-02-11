package dev.wads.motoridecallconnect.ui.social

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import dev.wads.motoridecallconnect.data.model.FriendRequest
import dev.wads.motoridecallconnect.data.model.UserProfile
import dev.wads.motoridecallconnect.ui.components.EmptyState

@Composable
fun SocialScreen(viewModel: SocialViewModel) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Amigos", "Solicitações", "Adicionar")
    val uiState by viewModel.uiState.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    text = { Text(title) },
                    selected = selectedTab == index,
                    onClick = { selectedTab = index }
                )
            }
        }

        when (selectedTab) {
            0 -> FriendsList(uiState.friends, viewModel)
            1 -> RequestsList(uiState.friendRequests, viewModel)
            2 -> AddFriendScreen(viewModel)
        }
    }
}

@Composable
fun FriendsList(friends: List<UserProfile>, viewModel: SocialViewModel) {
    if (friends.isEmpty()) {
        EmptyState(
            icon = Icons.Default.Person,
            title = "Sem amigos",
            description = "Adicione amigos para ver aqui."
        )
    } else {
        LazyColumn(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(friends) { friend ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.padding(end = 16.dp))
                        Column {
                            Text(text = friend.displayName.ifEmpty { "Usuário Desconhecido" }, style = MaterialTheme.typography.titleMedium)
                            Text(text = "ID: ${friend.uid}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RequestsList(requests: List<FriendRequest>, viewModel: SocialViewModel) {
    if (requests.isEmpty()) {
        EmptyState(
            icon = Icons.Default.Archive,
            title = "Sem solicitações",
            description = "Nenhuma solicitação de amizade pendente."
        )
    } else {
        LazyColumn(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(requests) { request ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = request.fromName, style = MaterialTheme.typography.titleMedium)
                            Text(text = request.fromUid, style = MaterialTheme.typography.bodySmall)
                        }
                        Row {
                            IconButton(onClick = { viewModel.acceptRequest(request) }) {
                                Icon(Icons.Default.Check, contentDescription = "Aceitar", tint = MaterialTheme.colorScheme.primary)
                            }
                            IconButton(onClick = { viewModel.rejectRequest(request) }) {
                                Icon(Icons.Default.Close, contentDescription = "Rejeitar", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AddFriendScreen(viewModel: SocialViewModel) {
    var targetId by remember { mutableStateOf("") }
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val myId = viewModel.getMyId() ?: ""

    LaunchedEffect(uiState.addFriendSuccess) {
        if (uiState.addFriendSuccess) {
            Toast.makeText(context, "Solicitação enviada!", Toast.LENGTH_SHORT).show()
            targetId = ""
            viewModel.clearSuccess()
        }
    }

    LaunchedEffect(uiState.error) {
        if (uiState.error != null) {
            Toast.makeText(context, "Erro: ${uiState.error}", Toast.LENGTH_LONG).show()
            viewModel.clearError()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "Seu ID:", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = myId, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = {
                        clipboardManager.setText(AnnotatedString(myId))
                        Toast.makeText(context, "Copiado!", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copiar")
                    }
                }
            }
        }

        Divider()

        Text(text = "Adicionar Amigo", style = MaterialTheme.typography.headlineSmall)
        
        OutlinedTextField(
            value = targetId,
            onValueChange = { targetId = it },
            label = { Text("ID do Amigo") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Button(
            onClick = { viewModel.sendFriendRequest(targetId) },
            enabled = targetId.isNotBlank() && !uiState.isLoading,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (uiState.isLoading) {
                CircularProgressIndicator(modifier = Modifier.height(24.dp).width(24.dp), strokeWidth = 2.dp)
            } else {
                Text("Enviar Solicitação")
            }
        }
    }
}
