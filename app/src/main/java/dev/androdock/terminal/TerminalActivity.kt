package dev.androdock.terminal

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import dev.androdock.AndrodockApp
import dev.androdock.machines.ShellCommand

/**
 * Terminal plein écran (émulateur de Termux) : shell d'une machine Linux,
 * shell Docker (client docker préconfiguré) ou `docker exec` dans un conteneur.
 */
class TerminalActivity : Activity() {

    private lateinit var terminalView: TerminalView
    private var session: TerminalSession? = null
    private var ctrlDown = false
    private var altDown = false
    private var fontSizePx = 0f
    private lateinit var ctrlButton: Button
    private lateinit var altButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val command = try {
            resolveCommand()
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: "Commande impossible", Toast.LENGTH_LONG).show()
            finish(); return
        }
        title = intent.getStringExtra(EXTRA_TITLE) ?: "Terminal"

        fontSizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13f, resources.displayMetrics)

        terminalView = TerminalView(this, null).apply {
            setBackgroundColor(Color.BLACK)
            isFocusable = true
            isFocusableInTouchMode = true
            setTypeface(Typeface.MONOSPACE)
            setTextSize(fontSizePx.toInt())
            setTerminalViewClient(viewClient)
            keepScreenOn = true
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            addView(terminalView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(buildExtraKeys())
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        setContentView(root)

        val s = TerminalSession(
            command.executable,
            command.cwd,
            command.args.toTypedArray(),
            command.env.map { (k, v) -> "$k=$v" }.toTypedArray(),
            5000,
            sessionClient,
        )
        session = s
        terminalView.attachSession(s)
        terminalView.requestFocus()
    }

    private fun resolveCommand(): ShellCommand {
        val app = application as AndrodockApp
        val arg = intent.getStringExtra(EXTRA_ARG).orEmpty()
        return when (intent.getStringExtra(EXTRA_KIND)) {
            KIND_MACHINE -> app.machines.shell(arg)
            KIND_EXEC -> app.machines.containerShell(arg)
            else -> app.machines.dockerShell()
        }
    }

    private fun buildExtraKeys(): HorizontalScrollView {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun key(label: String, onClick: (Button) -> Unit): Button = Button(this).apply {
            text = label
            isAllCaps = false
            minWidth = 0; minimumWidth = 0
            setPadding(28, 0, 28, 0)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { onClick(this); terminalView.requestFocus() }
            row.addView(this)
        }
        key("ESC") { sendKey(KeyEvent.KEYCODE_ESCAPE) }
        key("TAB") { sendKey(KeyEvent.KEYCODE_TAB) }
        ctrlButton = key("CTRL") { ctrlDown = !ctrlDown; refreshModifiers() }
        altButton = key("ALT") { altDown = !altDown; refreshModifiers() }
        key("←") { sendKey(KeyEvent.KEYCODE_DPAD_LEFT) }
        key("↑") { sendKey(KeyEvent.KEYCODE_DPAD_UP) }
        key("↓") { sendKey(KeyEvent.KEYCODE_DPAD_DOWN) }
        key("→") { sendKey(KeyEvent.KEYCODE_DPAD_RIGHT) }
        for (c in listOf("-", "/", "|", "~", "&", ";")) key(c) { session?.write(c) }
        key("HOME") { sendKey(KeyEvent.KEYCODE_MOVE_HOME) }
        key("END") { sendKey(KeyEvent.KEYCODE_MOVE_END) }
        key("PGUP") { sendKey(KeyEvent.KEYCODE_PAGE_UP) }
        key("PGDN") { sendKey(KeyEvent.KEYCODE_PAGE_DOWN) }
        key("COLLER") { paste() }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(Color.rgb(30, 32, 40))
            addView(row)
        }
    }

    private fun refreshModifiers() {
        ctrlButton.setTextColor(if (ctrlDown) Color.rgb(79, 140, 255) else Color.WHITE)
        altButton.setTextColor(if (altDown) Color.rgb(79, 140, 255) else Color.WHITE)
    }

    private fun sendKey(code: Int) {
        var meta = 0
        if (ctrlDown) meta = meta or KeyEvent.META_CTRL_ON
        if (altDown) meta = meta or KeyEvent.META_ALT_ON
        terminalView.onKeyDown(code, KeyEvent(0, 0, KeyEvent.ACTION_DOWN, code, 0, meta))
        ctrlDown = false; altDown = false; refreshModifiers()
    }

    private fun paste() {
        val clip = getSystemService(ClipboardManager::class.java).primaryClip ?: return
        val text = clip.getItemAt(0)?.coerceToText(this)?.toString() ?: return
        session?.emulator?.paste(text)
    }

    override fun onDestroy() {
        session?.finishIfRunning()
        super.onDestroy()
    }

    private val sessionClient = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) { terminalView.onScreenUpdated() }
        override fun onTitleChanged(changedSession: TerminalSession) {}
        override fun onSessionFinished(finishedSession: TerminalSession) {
            Toast.makeText(this@TerminalActivity, "Session terminée — touche Entrée pour fermer", Toast.LENGTH_SHORT).show()
        }
        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("terminal", text ?: ""))
        }
        override fun onPasteTextFromClipboard(session: TerminalSession?) { paste() }
        override fun onBell(session: TerminalSession) {}
        override fun onColorsChanged(session: TerminalSession) {}
        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun getTerminalCursorStyle(): Int? = null
        override fun logError(tag: String?, message: String?) { Log.e(tag, message.orEmpty()) }
        override fun logWarn(tag: String?, message: String?) { Log.w(tag, message.orEmpty()) }
        override fun logInfo(tag: String?, message: String?) { Log.i(tag, message.orEmpty()) }
        override fun logDebug(tag: String?, message: String?) { Log.d(tag, message.orEmpty()) }
        override fun logVerbose(tag: String?, message: String?) { Log.v(tag, message.orEmpty()) }
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { Log.e(tag, message, e) }
        override fun logStackTrace(tag: String?, e: Exception?) { Log.e(tag, "erreur", e) }
    }

    private val viewClient = object : TerminalViewClient {
        override fun onScale(scale: Float): Float {
            if (scale < 0.9f || scale > 1.1f) {
                fontSizePx = (fontSizePx * scale).coerceIn(14f, 80f)
                terminalView.setTextSize(fontSizePx.toInt())
                return 1.0f
            }
            return scale
        }

        override fun onSingleTapUp(e: MotionEvent?) {
            terminalView.requestFocus()
            getSystemService(InputMethodManager::class.java).showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
        }

        override fun shouldBackButtonBeMappedToEscape(): Boolean = false
        override fun shouldEnforceCharBasedInput(): Boolean = true
        override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
        override fun isTerminalViewSelected(): Boolean = true
        override fun copyModeChanged(copyMode: Boolean) {}

        override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean {
            if (session?.isRunning == false && keyCode == KeyEvent.KEYCODE_ENTER) {
                finish(); return true
            }
            return false
        }

        override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false
        override fun onLongPress(event: MotionEvent?): Boolean = false
        override fun readControlKey(): Boolean = ctrlDown
        override fun readAltKey(): Boolean = altDown
        override fun readShiftKey(): Boolean = false
        override fun readFnKey(): Boolean = false

        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean {
            if (this@TerminalActivity.ctrlDown || altDown) {
                this@TerminalActivity.ctrlDown = false; altDown = false
                runOnUiThread { refreshModifiers() }
            }
            return false
        }

        override fun onEmulatorSet() {}
        override fun logError(tag: String?, message: String?) { Log.e(tag, message.orEmpty()) }
        override fun logWarn(tag: String?, message: String?) { Log.w(tag, message.orEmpty()) }
        override fun logInfo(tag: String?, message: String?) { Log.i(tag, message.orEmpty()) }
        override fun logDebug(tag: String?, message: String?) { Log.d(tag, message.orEmpty()) }
        override fun logVerbose(tag: String?, message: String?) { Log.v(tag, message.orEmpty()) }
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { Log.e(tag, message, e) }
        override fun logStackTrace(tag: String?, e: Exception?) { Log.e(tag, "erreur", e) }
    }

    companion object {
        const val EXTRA_KIND = "kind"
        const val EXTRA_ARG = "arg"
        const val EXTRA_TITLE = "title"
        const val KIND_MACHINE = "machine"
        const val KIND_DOCKER = "docker"
        const val KIND_EXEC = "exec"

        fun intent(context: Context, kind: String, arg: String? = null, title: String? = null) =
            Intent(context, TerminalActivity::class.java)
                .putExtra(EXTRA_KIND, kind).putExtra(EXTRA_ARG, arg).putExtra(EXTRA_TITLE, title)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
    }
}
