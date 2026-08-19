package com.cuttercommand.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(), PlotterClient.Listener {

    private lateinit var client: PlotterClient
    private lateinit var statusText: TextView
    private lateinit var versionText: TextView
    private lateinit var logText: TextView
    private lateinit var fileNameText: TextView
    private lateinit var sendButton: Button
    private lateinit var ipInput: EditText
    private lateinit var portInput: EditText
    private lateinit var usValueText: TextView
    private lateinit var vsValueText: TextView
    private lateinit var fsValueText: TextView

    private var fileContent: String? = null

    private val prefs by lazy { getSharedPreferences("cutter_command_prefs", Context.MODE_PRIVATE) }

    // Storage Access Framework - no WRITE/READ_EXTERNAL_STORAGE permission needed,
    // works correctly under scoped storage on every modern Android version.
    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) loadFile(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))

        statusText = findViewById(R.id.statusText)
        versionText = findViewById(R.id.versionText)
        logText = findViewById(R.id.logText)
        fileNameText = findViewById(R.id.fileNameText)
        sendButton = findViewById(R.id.sendButton)
        ipInput = findViewById(R.id.ipInput)
        portInput = findViewById(R.id.portInput)
        usValueText = findViewById(R.id.usValueText)
        vsValueText = findViewById(R.id.vsValueText)
        fsValueText = findViewById(R.id.fsValueText)

        ipInput.setText(prefs.getString("ip", "192.168.16.254"))
        portInput.setText(prefs.getString("port", "8080"))

        client = PlotterClient(this)

        findViewById<Button>(R.id.connectButton).setOnClickListener {
            val ip = ipInput.text.toString()
            val port = portInput.text.toString().toIntOrNull() ?: 8080
            prefs.edit().putString("ip", ip).putString("port", port.toString()).apply()
            client.connect(ip, port)
        }

        findViewById<Button>(R.id.disconnectButton).setOnClickListener {
            client.disconnect()
        }

        findViewById<Button>(R.id.pickFileButton).setOnClickListener {
            filePicker.launch(arrayOf("*/*"))
        }

        sendButton.setOnClickListener {
            val content = fileContent ?: return@setOnClickListener
            client.sendProgram(content)
        }

        // --- manual jog pad: hold to move, release to stop, same as the original app ---
        setupJogButton(findViewById(R.id.jogForwardButton), PlotterClient.JogDirection.FORWARD)
        setupJogButton(findViewById(R.id.jogBackButton), PlotterClient.JogDirection.BACK)
        setupJogButton(findViewById(R.id.jogLeftButton), PlotterClient.JogDirection.LEFT)
        setupJogButton(findViewById(R.id.jogRightButton), PlotterClient.JogDirection.RIGHT)

        // The jog pad's center button is a jog-speed mode toggle (Normal /
        // Fast), not a stop - client.stop() is still reachable via the
        // separate Stop button next to Start cut.
        jogModeButton = findViewById(R.id.jogModeButton)
        jogModeDefaultTint = jogModeButton.backgroundTintList
        updateJogModeButtonAppearance()
        jogModeButton.setOnClickListener {
            jogFastMode = !jogFastMode
            updateJogModeButtonAppearance()
        }

        findViewById<Button>(R.id.stopButton).setOnClickListener { client.stop() }

        findViewById<Button>(R.id.pauseButton).setOnClickListener {
            client.pause()
        }

        findViewById<Button>(R.id.testButton).setOnClickListener {
            client.test()
        }
    }

    // Each jog button press runs a repeating cycle for as long as it's
    // held: send the move command, wait (Fast mode only), send the stop
    // command - then, if still held, immediately start the next cycle.
    // Every cycle always finishes (sends its stop) even if the button was
    // released mid-cycle - release just stops a new cycle from starting
    // after the current one completes.
    //
    // Normal mode has no deliberate delay between action and stop, but a
    // small minimum pacing gap is still enforced below (see
    // jogMinCycleGapMs) - a literal zero-delay repeating loop would spin
    // with no suspension point, which would freeze the app's UI thread and
    // flood the connection. That floor is a technical necessity, not part
    // of the requested behavior, and can be tuned.
    private var jogFastMode = true // Fast is the default mode
    private lateinit var jogModeButton: Button
    private var jogModeDefaultTint: ColorStateList? = null
    private val jogFastStopDelayMs = 500L
    private val jogMinCycleGapMs = 50L
    private val jogNormalModeColor = Color.parseColor("#C62828")

    private fun updateJogModeButtonAppearance() {
        jogModeButton.backgroundTintList =
            if (jogFastMode) jogModeDefaultTint else ColorStateList.valueOf(jogNormalModeColor)
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun setupJogButton(button: Button, direction: PlotterClient.JogDirection) {
        // held/cycleJob are captured per-button (not shared Activity fields),
        // so two different jog directions can't interfere with each other's
        // cycle state. held is an AtomicBoolean, not a plain var, because
        // it's written from the UI thread (touch events) and read from the
        // cycle loop's background thread (Dispatchers.Default) - a plain
        // var doesn't guarantee that write is visible to the other thread
        // promptly.
        val held = java.util.concurrent.atomic.AtomicBoolean(false)
        var cycleJob: Job? = null

        button.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    held.set(true)
                    if (cycleJob?.isActive != true) {
                        cycleJob = lifecycleScope.launch(Dispatchers.Default) {
                            do {
                                client.jogPress(direction)
                                if (jogFastMode) {
                                    delay(jogFastStopDelayMs)
                                } else {
                                    delay(jogMinCycleGapMs)
                                }
                                client.jogRelease()
                            } while (held.get())
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    held.set(false)
                }
            }
            false // let the button still show its normal press/click visuals
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_errors -> showHtmlDialog("Error messages", getString(R.string.error_reference_text))
            R.id.menu_help -> showTextDialog("Help", getString(R.string.help_text))
            R.id.menu_about -> showTextDialog("About", getString(R.string.about_text, getString(R.string.app_version)))
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun showTextDialog(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Close", null)
            .show()
    }

    // Error messages uses HTML markup (bold headers, line breaks) for
    // readability instead of one long word-wrapped block of plain text.
    private fun showHtmlDialog(title: String, htmlMessage: String) {
        val formatted = android.text.Html.fromHtml(htmlMessage, android.text.Html.FROM_HTML_MODE_LEGACY)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(formatted)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun loadFile(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                val bytes = input.readBytes()
                // UTF-8, matching the reference tooling this file format
                // actually comes from (see PlotterClient.sendProgram).
                fileContent = String(bytes, Charsets.UTF_8)
            }
            fileNameText.text = resolveFileName(uri)
            sendButton.isEnabled = true
            updateSpeedPressureDisplay(fileContent ?: "")
        } catch (e: Exception) {
            onLog("Failed to read file: ${e.message}")
        }
    }

    // Looks for US<n>;, VS<n>;, and !FS<n>; in the loaded file (the format
    // a header like "IN;TB25,11320,7840;CT1;PA;P0;US7;VS8;!FS10;" uses).
    // Each is matched independently - a file might only define some of the
    // three. Anything not found in the file shows "Machine Setting", since
    // the app no longer sends its own override for these - whatever is
    // already configured on the cutter applies in that case.
    private val usPattern = Regex("(?<=[;\r\n]|^)US(\\d+);")
    private val vsPattern = Regex("(?<=[;\r\n]|^)VS(\\d+);")
    private val fsPattern = Regex("(?<=[;\r\n]|^)!FS(\\d+);")

    private fun updateSpeedPressureDisplay(content: String) {
        usValueText.text = usPattern.find(content)?.groupValues?.get(1) ?: "Machine Setting"
        vsValueText.text = vsPattern.find(content)?.groupValues?.get(1) ?: "Machine Setting"
        fsValueText.text = fsPattern.find(content)?.groupValues?.get(1) ?: "Machine Setting"
    }

    // Uri.lastPathSegment often returns an opaque provider ID (e.g. "msf:1234")
    // rather than a real filename. DocumentsContract.getDocumentId() sometimes
    // returns a genuine relative path like "primary:Download/cutfile.plt" - but
    // for other providers it returns an opaque internal ID after the colon too
    // (e.g. a bare row number), which is not useful to show. We only trust the
    // colon-split result if it actually contains a "/" (i.e. looks like a real
    // folder path); otherwise we fall back to the reliable DISPLAY_NAME column,
    // which every document provider is required to support.
    private fun resolveFileName(uri: Uri): String {
        var displayName: String? = null
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) {
                    displayName = cursor.getString(nameIndex)?.takeIf { it.isNotBlank() }
                }
            }
        } catch (_: Exception) {
            // fall through - displayName stays null
        }

        try {
            val docId = DocumentsContract.getDocumentId(uri)
            val colonIdx = docId.indexOf(':')
            if (colonIdx != -1 && colonIdx < docId.length - 1) {
                val afterColon = docId.substring(colonIdx + 1)
                if (afterColon.contains('/')) {
                    return afterColon
                }
            }
        } catch (_: Exception) {
            // not a document-backed Uri, or this provider doesn't support getDocumentId
        }

        return displayName ?: uri.lastPathSegment ?: "file selected"
    }

    override fun onStatus(text: String) {
        statusText.text = text
    }

    override fun onVersion(version: String) {
        versionText.text = "Firmware: $version"
    }

    override fun onLog(line: String) {
        logText.append(line + "\n")
    }

    override fun onDestroy() {
        super.onDestroy()
        client.disconnect()
    }
}
