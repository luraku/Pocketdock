package dev.androdock.vm

/**
 * Nettoie une ligne de la console série de la VM pour l'affichage : la console reçoit les codes
 * de contrôle d'un vrai terminal (barre de progression de wget : ESC 7 / ESC 8, ESC[0K, retours
 * chariot), qui s'affichaient tels quels (« 8[0K7 95% ### »).
 */
object ConsoleText {
    private val csi = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")   // ESC [ … (couleurs, effacement)
    private val esc = Regex("\u001B[@-Z\\\\-_0-9=>]")          // ESC 7, ESC 8, ESC M…
    private val ctrl = Regex("[\u0000-\u0008\u000B-\u001F\u007F]")

    private val progress = Regex("^\\s*\\d{1,3}%( |$)")

    /** Ligne de barre de progression (« 95% ##### ») : remplace la précédente au lieu de s'empiler. */
    fun isProgress(line: String): Boolean = progress.containsMatchIn(line)

    /** Ligne lisible, ou null si elle ne contient plus rien. */
    fun clean(raw: String): String? {
        // Retour chariot : le terminal réécrit la ligne, seul le dernier segment reste visible.
        val visible = raw.trimEnd('\r').substringAfterLast('\r')
        val text = visible.replace(csi, "").replace(esc, "").replace(ctrl, "").trimEnd()
        return text.ifBlank { null }
    }
}
