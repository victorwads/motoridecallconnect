package dev.wads.motoridecallconnect.ui.login

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.firebase.auth.FirebaseAuth
import dev.wads.motoridecallconnect.R

@Composable
fun LoginScreen(
    viewModel: LoginViewModel,
    onLoginSuccess: (isFirebase: Boolean) -> Unit
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    var showLocalModeWarning by remember { mutableStateOf(false) }

    val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
        .requestIdToken(context.getString(R.string.default_web_client_id))
        .requestEmail()
        .build()

    val googleSignInClient = GoogleSignIn.getClient(context, gso)

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            viewModel.handleGoogleSignInResult(task)
        }
    }

    LaunchedEffect(uiState) {
        when (uiState) {
            is LoginUiState.Success -> {
                val isFirebase = FirebaseAuth.getInstance().currentUser != null
                onLoginSuccess(isFirebase)
            }
            is LoginUiState.Error -> {
                Toast.makeText(
                    context,
                    (uiState as LoginUiState.Error).message,
                    Toast.LENGTH_LONG
                ).show()
            }
            else -> {}
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (uiState is LoginUiState.Loading) {
            CircularProgressIndicator()
        } else {
            Text(
                text = stringResource(id = R.string.app_name),
                style = MaterialTheme.typography.headlineMedium
            )
            Spacer(modifier = Modifier.height(32.dp))
            Button(
                onClick = {
                    launcher.launch(googleSignInClient.signInIntent)
                },
                modifier = Modifier.fillMaxWidth(0.8f)
            ) {
                Text(text = "Entrar com Google")
            }
            Spacer(modifier = Modifier.height(16.dp))
            dev.wads.motoridecallconnect.ui.components.BigButton(
                text = "Usar sem conta (Modo Local)",
                onClick = { showLocalModeWarning = true },
                variant = dev.wads.motoridecallconnect.ui.components.ButtonVariant.Outline,
                fullWidth = false,
                modifier = Modifier.fillMaxWidth(0.8f)
            )

            if (showLocalModeWarning) {
                AlertDialog(
                    onDismissRequest = { showLocalModeWarning = false },
                    title = { Text(text = "Atenção") },
                    text = { Text(text = "No modo sem conta, seus dados (viagens e transcrições) serão salvos apenas no dispositivo. Se você desinstalar o app ou entrar em uma conta futuramente, esses dados locais serão perdidos.") },
                    confirmButton = {
                        TextButton(onClick = {
                            showLocalModeWarning = false
                            viewModel.useLocalMode()
                        }) {
                            Text("Continuar")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showLocalModeWarning = false }) {
                            Text("Cancelar")
                        }
                    }
                )
            }
        }
    }
}
