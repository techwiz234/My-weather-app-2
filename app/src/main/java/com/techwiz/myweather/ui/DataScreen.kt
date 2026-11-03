package com.techwiz.myweather.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/** Where the weather numbers come from, and a way to point the app at your own server. */
@Composable
fun DataScreen(
    state: UiState,
    onSaveUrl: (String) -> Unit,
    onSaveToken: (String, String) -> Unit,
    onTest: (String) -> Unit,
    onFallback: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var url by remember { mutableStateOf(state.serverUrl) }
    var tokenId by remember { mutableStateOf(state.accessId) }
    var tokenSecret by remember { mutableStateOf(state.accessSecret) }

    Column(
        modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Data source", style = MaterialTheme.typography.headlineMedium)

        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Your weather server", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Public weather APIs cap how much you can use them. Run your own copy of Open-Meteo " +
                        "at home (setup guide: server/README.md in the project) and the app talks only to " +
                        "it, with no limits.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Server address") },
                    placeholder = { Text("100.101.102.103:8080") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Cloudflare Access token (optional)", style = MaterialTheme.typography.titleSmall)
                Text(
                    "If your server is behind Cloudflare Access, paste the service token's Client ID and " +
                        "Secret. They are sent only to your server's address.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = tokenId,
                    onValueChange = { tokenId = it },
                    label = { Text("Client ID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = tokenSecret,
                    onValueChange = { tokenSecret = it },
                    label = { Text("Client Secret") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { onSaveToken(tokenId, tokenSecret); onSaveUrl(url) }) { Text("Save") }
                    OutlinedButton(
                        onClick = { onSaveToken(tokenId, tokenSecret); onTest(url) },
                        enabled = !state.testing,
                    ) { Text("Test") }
                    if (state.testing) CircularProgressIndicator(Modifier.padding(start = 4.dp), strokeWidth = 2.dp)
                }
                state.serverTest?.let {
                    Text(
                        (if (it.ok) "✓ " else "✗ ") + it.message,
                        color = if (it.ok) GoColor else NoGoColor,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(
                    "Leave blank to use the public Open-Meteo API instead.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Fall back to the public API", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "If your server is off or unreachable, use the public Open-Meteo API so you " +
                                "still get a forecast. The public API has daily limits.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = state.useFallback,
                        onCheckedChange = onFallback,
                        enabled = state.serverUrl.isNotBlank(),
                    )
                }
            }
        }

        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Last forecast came from", style = MaterialTheme.typography.titleMedium)
                Text(state.forecast?.source?.ifEmpty { "–" } ?: "–")
                if (state.forecast?.source?.contains("fallback") == true) {
                    Text(
                        "Your server didn't answer, so this used the public API.",
                        color = CautionColor,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Other outside services", style = MaterialTheme.typography.titleMedium)
                Text(
                    "These are not covered by your server. Each is free with no key, but they are other " +
                        "people's servers and could rate-limit heavy use:",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                listOf(
                    "Official US alerts: api.weather.gov (US government)",
                    "Radar: RainViewer, fallback Iowa Environmental Mesonet",
                    "Satellite: NASA GIBS",
                    "Base map: CARTO / OpenStreetMap",
                    "Place search: your phone's own geocoder, fallback Open-Meteo geocoding",
                ).forEach { Text("•  $it", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
