package dev.androdock.legal

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.androdock.AndrodockApp
import dev.androdock.ui.AndrodockTheme
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Une entrée de la page : nom, licence, lien, et textes de licence dépliables. */
private data class Notice(
    val title: String,
    val license: String,
    val url: String?,
    val details: String? = null,
    val texts: List<Pair<String, String>> = emptyList(),
)

/**
 * Licence de Pocketdock (Apache 2.0) et avis des logiciels tiers : bibliothèques de l'app,
 * programmes natifs embarqués (liste générée au build par scripts/fetch-native.py, avec les
 * textes complets et la provenance) et composants téléchargés par l'appareil.
 */
class LicensesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val sections = load()
        setContent { AndrodockTheme { Surface(Modifier.fillMaxSize()) { Screen(sections) { finish() } } } }
    }

    private fun asset(path: String): String? = runCatching { assets.open(path).use { it.readBytes().decodeToString() } }.getOrNull()

    private fun load(): List<Pair<String, List<Notice>>> {
        val json = Json { ignoreUnknownKeys = true }
        val own = listOf(
            Notice(
                "Pocketdock", "Apache-2.0", "https://github.com/luraku/Androdock",
                "Copyright 2026 luraku et les contributeurs de Pocketdock.",
                listOfNotNull(asset("licenses/apache-2.0.txt")?.let { "LICENSE" to it }),
            )
        )
        val abi = (application as AndrodockApp).runtime.abi
        val native = asset("licenses/native-$abi.json")?.let { raw ->
            json.parseToJsonElement(raw).jsonObject["components"]!!.jsonArray.map { el ->
                val c = el.jsonObject
                fun str(k: String) = c[k]?.jsonPrimitive?.content.orEmpty()
                Notice(
                    title = "${str("name")} ${str("version")}",
                    license = (c["licenses"] as? JsonArray)?.joinToString(", ") { it.jsonPrimitive.content }.orEmpty(),
                    url = str("homepage").ifBlank { null },
                    details = "Fichiers : ${(c["files"] as? JsonArray)?.joinToString(", ") { it.jsonPrimitive.content }}\n" +
                        "Paquet d'origine : ${str("package_file")}\nSHA-256 : ${str("sha256")}\nSources : ${str("source")}",
                    texts = (c["license_texts"] as? JsonArray)?.map { t ->
                        t.jsonObject["file"]!!.jsonPrimitive.content to t.jsonObject["text"]!!.jsonPrimitive.content
                    }.orEmpty(),
                )
            }
        } ?: listOf(Notice("Liste indisponible", "", null, "Cet APK a été compilé sans scripts/fetch-native.py."))
        val libs = asset("licenses/libraries.json")?.let { json.parseToJsonElement(it).jsonObject } ?: JsonObject(emptyMap())
        fun list(key: String) = (libs[key] as? JsonArray)?.map { el ->
            val o = el.jsonObject
            Notice(
                o["name"]!!.jsonPrimitive.content, o["license"]!!.jsonPrimitive.content,
                o["url"]?.jsonPrimitive?.content, o["note"]?.jsonPrimitive?.content,
            )
        }.orEmpty()
        return listOf(
            "Pocketdock" to own,
            "Programmes natifs embarqués ($abi)" to native,
            "Bibliothèques de l'application" to list("libraries"),
            "Téléchargés par l'appareil (hors APK)" to list("runtime"),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Screen(sections: List<Pair<String, List<Notice>>>, onBack: () -> Unit) {
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Licences et mentions") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") } },
        )
    }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                Text(
                    "Pocketdock est distribué sous licence Apache 2.0. Les programmes natifs (QEMU, proot…) " +
                        "sont des logiciels libres distribués sous leurs propres licences, notamment la GNU GPL v2 : " +
                        "leurs sources sont indiquées pour chacun.",
                    Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium,
                )
            }
            for ((title, notices) in sections) {
                item {
                    Text(title, Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
                items(notices) { NoticeRow(it); HorizontalDivider() }
            }
        }
    }
}

@Composable
private fun NoticeRow(n: Notice) {
    var open by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val expandable = n.details != null || n.texts.isNotEmpty()
    Column(Modifier.fillMaxWidth().clickable(enabled = expandable) { open = !open }.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(n.title, style = MaterialTheme.typography.bodyLarge)
                if (n.license.isNotBlank()) Text(n.license, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (expandable) Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
        }
        if (open) {
            n.url?.let { url ->
                Text(url, Modifier.padding(top = 6.dp).clickable {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                }, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            }
            n.details?.let { Text(it, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall) }
            for ((file, text) in n.texts) {
                Text(file, Modifier.padding(top = 10.dp), style = MaterialTheme.typography.labelMedium)
                Text(
                    text.trim(), Modifier.padding(top = 4.dp).horizontalScroll(rememberScrollState()),
                    fontFamily = FontFamily.Monospace, fontSize = 10.sp,
                )
            }
        }
    }
}
