package dev.androdock.control

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import dev.androdock.AndrodockApp
import dev.androdock.R
import dev.androdock.ui.AndrodockTheme

/**
 * Accord de l'utilisateur à une demande d'accès d'une application tierce à l'API de contrôle.
 * Ouvert depuis la notification « Demande d'accès à Pocketdock ».
 */
class ConsentActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as AndrodockApp
        val id = intent.getStringExtra(EXTRA_ID).orEmpty()
        val request = app.control.clients.pairing(id)?.takeIf { it.state == PairRequest.State.PENDING }
        setContent {
            AndrodockTheme {
                Surface(Modifier.fillMaxSize()) {
                    if (request == null) {
                        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text("Demande expirée", style = MaterialTheme.typography.titleLarge)
                            Text("Cette demande d'accès a déjà été traitée ou a expiré (5 minutes).")
                            Button(onClick = { finish() }) { Text("Fermer") }
                        }
                    } else {
                        val selected = remember { mutableStateListOf<Scope>().apply { addAll(request.scopes.sortedBy { it.ordinal }) } }
                        Column(
                            Modifier.padding(24.dp).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Text("Demande d'accès à Pocketdock", style = MaterialTheme.typography.titleLarge)
                            Text("« ${request.name} » veut piloter Pocketdock.", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Ce nom est déclaré par l'application elle-même et ne peut pas être vérifié. " +
                                    "N'acceptez que si vous venez de lancer cette demande depuis une application de confiance.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Card {
                                Column(Modifier.padding(vertical = 8.dp)) {
                                    request.scopes.sortedBy { it.ordinal }.forEach { s ->
                                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Checkbox(s in selected, { on -> if (on) selected.add(s) else selected.remove(s) })
                                            Text(s.label, style = MaterialTheme.typography.bodyMedium)
                                        }
                                    }
                                }
                            }
                            Text(
                                "Vous pourrez retirer cet accès à tout moment : tableau de bord → Moteur → API de contrôle.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row {
                                OutlinedButton(onClick = {
                                    runCatching { app.control.clients.deny(request.id) }
                                    done(request.id)
                                }) { Text("Refuser") }
                                Spacer(Modifier.width(12.dp))
                                Button(enabled = selected.isNotEmpty(), onClick = {
                                    runCatching { app.control.clients.approve(request.id, selected.toSet()) }
                                    done(request.id)
                                }) { Text("Autoriser") }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun done(id: String) {
        getSystemService(NotificationManager::class.java).cancel(notificationId(id))
        finish()
    }

    companion object {
        const val EXTRA_ID = "request"

        private fun notificationId(id: String) = 0x5000 + (id.hashCode() and 0xfff)

        /** Notification de demande d'accès : la toucher ouvre l'écran d'accord. */
        fun notify(context: Context, r: PairRequest) {
            val open = PendingIntent.getActivity(
                context, notificationId(r.id),
                Intent(context, ConsentActivity::class.java).putExtra(EXTRA_ID, r.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val rights = r.scopes.sortedBy { it.ordinal }.joinToString(", ") { it.id }
            val n = NotificationCompat.Builder(context, AndrodockApp.CHANNEL_ACCESS)
                .setSmallIcon(R.drawable.ic_stat_engine)
                .setContentTitle("Demande d'accès à Pocketdock")
                .setContentText("« ${r.name} » demande : $rights")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                .setAutoCancel(true)
                .setTimeoutAfter(AppClients.PENDING_TTL_MS)
                .setContentIntent(open)
                .build()
            runCatching { context.getSystemService(NotificationManager::class.java).notify(notificationId(r.id), n) }
        }
    }
}
