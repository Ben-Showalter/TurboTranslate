package com.turbotranslate.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File

/**
 * Live push-to-talk conversation translator — this is the app's one and
 * only screen (launcher activity).
 *
 * Right softkey: hold while the English speaker talks, release to
 * transcribe (Whisper via GroqVoiceInputHelper) and translate into the
 * "other language" (GroqTranslateHelper).
 *
 * Left softkey: same thing in reverse — the other-language speaker talks,
 * result is translated to English.
 *
 * D-pad Up/Down: step through the transcript. A message that fits the
 * screen jumps straight to it; a longer one scrolls gradually so you can
 * read all of it before moving to the next. Center/OK: copy the
 * highlighted message's translation to the clipboard.
 *
 * `*` key: opens LanguagePickerActivity to change the other language.
 *
 * `#` key: takes a photo (same camera-capture pattern as FlipText's photo
 * attach), sends it to a Groq vision model to read and translate any
 * text in it, and adds the result to the transcript.
 *
 * `0` key (or Menu): opens Options — change language, take photo, check
 * GitHub for a newer version (UpdateChecker). The update check also runs
 * on its own about once a week when the app is opened.
 */
class TranslatorActivity : AppCompatActivity() {

    private enum class RecordSide { ENGLISH, OTHER }

    private lateinit var voiceHelper: GroqVoiceInputHelper
    private lateinit var adapter: TranslatorAdapter
    private lateinit var transcriptList: RecyclerView
    private lateinit var listeningIndicator: TextView
    private lateinit var titleBar: TextView
    private lateinit var softLeftLabel: TextView
    private lateinit var softRightLabel: TextView

    private var recordingSide: RecordSide? = null
    private var pendingRecordSide: RecordSide? = null

    // Set true the moment a DOWN for that key is actually seen. If a
    // corresponding UP arrives with this still false, the OS never
    // delivered the DOWN for that press at all — confirmed on this
    // device for the right softkey (logcat shows UP with no preceding
    // DOWN, ever), the same class of quirk as the swallowed camera key
    // documented in FlipText's README. In that case there's no way to
    // know a press started until it's already over, so that UP is
    // treated as a tap-toggle instead: first tap starts recording,
    // second tap stops it.
    private var rightKeySeenDown = false
    private var leftKeySeenDown = false

    // D-pad Up/Down moves this instead of raw-scrolling the list, so
    // center/OK has a specific message to act on (copy to clipboard).
    // followLatest keeps the selection pinned to the newest message
    // until the user deliberately navigates up into the history, same
    // idea as auto-scroll in a normal chat view.
    private var selectedPosition: Int? = null
    private var followLatest = true

    private var pendingPhotoUri: Uri? = null

    // An update check is in flight — keeps a repeat `0` press or resume
    // from starting a second one.
    private var updateCheckRunning = false

    // True between onPause and onResume, so a finished automatic update
    // check doesn't pop its prompt over the camera or language picker.
    private var isAway = false

    // Set when this window saw the DOWN of 0/Menu, so the UP of a press
    // that began elsewhere (or inside the Options dialog) is ignored.
    private var optionsKeySeenDown = false

    companion object {
        private const val RECORD_AUDIO_REQUEST_CODE = 300
        private const val CAMERA_PERMISSION_REQUEST_CODE = 301
        private const val STORAGE_PERMISSION_REQUEST_CODE = 302
        private const val CAMERA_CAPTURE_REQUEST_CODE = 400
        private const val TAG = "TurboTranslateActivity"

        // Asked at most once per process, so a declined prompt doesn't
        // come back on every resume.
        private var keyAccessPrompted = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_translator)

        voiceHelper = GroqVoiceInputHelper(this)
        val savedEntries = SettingsHelper.loadTranscript(this)
        adapter = TranslatorAdapter(savedEntries)

        transcriptList = findViewById(R.id.transcriptList)
        transcriptList.layoutManager = LinearLayoutManager(this)
        transcriptList.setItemViewCacheSize(10)
        transcriptList.adapter = adapter

        listeningIndicator = findViewById(R.id.listeningIndicator)
        titleBar = findViewById(R.id.titleBar)
        softLeftLabel = findViewById(R.id.softLeftLabel)
        softRightLabel = findViewById(R.id.softRightLabel)

        if (savedEntries.isNotEmpty()) {
            selectedPosition = savedEntries.size - 1
            followLatest = true
            adapter.setSelectedPosition(selectedPosition)
            // Layout hasn't happened yet on first frame — post so the
            // scroll runs after the RecyclerView actually has rows.
            transcriptList.post { transcriptList.scrollToPosition(selectedPosition!!) }
        }
    }

    override fun onResume() {
        super.onResume()
        isAway = false
        // The other language may have just changed in LanguagePickerActivity —
        // refresh every label that names it.
        val other = SettingsHelper.getOtherLanguage(this)
        titleBar.text = "English ⇄ $other  (* lang # photo 0 opts)"
        softLeftLabel.text = "Hold 7: $other"
        softRightLabel.text = "Hold 9: English"
        checkApiKeyAccess()
        maybeCheckForUpdate()
    }

    /**
     * The Groq key is read from GroqConfig.KEY_FILE_PATH, which needs
     * storage access. If there's no usable key yet, asks for that access
     * (or, with access already granted, says where to paste the key).
     */
    private fun checkApiKeyAccess() {
        // Creates the file once access exists, if no other Turbo app has.
        GroqConfig.ensureKeyFileExists()
        if (GroqConfig.getApiKey().isNotBlank() || keyAccessPrompted) return
        keyAccessPrompted = true
        if (hasStorageAccess()) {
            showMissingKeyToast()
        } else {
            requestStorageAccess()
        }
    }

    private fun hasStorageAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    private fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // The general list rather than the per-app deep link, which
            // doesn't resolve on these OEM builds (same as TurboVoice).
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (e: Exception) {
                // Toast below still gives the path.
            }
            Toast.makeText(
                this,
                "To read your Groq key: Settings > Apps > Special app access > All files access > TurboTranslate",
                Toast.LENGTH_LONG
            ).show()
        } else {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), STORAGE_PERMISSION_REQUEST_CODE
            )
        }
    }

    private fun showMissingKeyToast() {
        Toast.makeText(
            this,
            "No Groq API key — paste it into ${GroqConfig.KEY_FILE_PATH}",
            Toast.LENGTH_LONG
        ).show()
    }

    /**
     * Checks GitHub for a newer release about once a week (the app is
     * opened often enough that no background scheduler is needed) and, if
     * there is one, asks whether to install it. Same as Flip Launcher's
     * Home screen check; Options > Check for updates runs one on demand.
     */
    private fun maybeCheckForUpdate() {
        val now = System.currentTimeMillis()
        val last = SettingsHelper.getLastUpdateCheckAt(this)
        // A last-check time in the future means the clock was turned back; check anyway.
        if (now - last in 0 until UpdateChecker.CHECK_INTERVAL_MS) return
        if (updateCheckRunning || recordingSide != null) return
        // Recorded up front so resumes while the check is in flight don't start another.
        SettingsHelper.setLastUpdateCheckAt(this, now)
        updateCheckRunning = true
        fetchLatestUpdate { result ->
            updateCheckRunning = false
            if (result.isFailure) {
                // Offline or rate-limited: try again in a day rather than a week.
                SettingsHelper.setLastUpdateCheckAt(
                    this,
                    now - UpdateChecker.CHECK_INTERVAL_MS + UpdateChecker.RETRY_AFTER_FAILURE_MS
                )
            }
            val release = result.getOrNull() ?: return@fetchLatestUpdate
            if (recordingSide == null && pendingPhotoUri == null && !isAway) {
                showUpdatePrompt(release)
            } else {
                // Mid-conversation (or off at the camera/language picker) —
                // don't interrupt; ask on the next resume instead.
                SettingsHelper.setLastUpdateCheckAt(this, 0L)
            }
        }
    }

    /** Manual counterpart to [maybeCheckForUpdate], from Options. */
    private fun checkForUpdateNow() {
        if (updateCheckRunning || recordingSide != null) return
        Toast.makeText(this, "Checking for updates… (v${UpdateChecker.installedVersion(this)} installed)", Toast.LENGTH_SHORT).show()
        updateCheckRunning = true
        fetchLatestUpdate { result ->
            updateCheckRunning = false
            val release = result.getOrNull()
            when {
                result.isFailure ->
                    Toast.makeText(this, "Couldn't check for updates", Toast.LENGTH_LONG).show()
                release == null ->
                    Toast.makeText(this, "TurboTranslate is up to date", Toast.LENGTH_SHORT).show()
                else -> showUpdatePrompt(release)
            }
        }
    }

    override fun onPause() {
        isAway = true
        super.onPause()
    }

    private fun startRecording(side: RecordSide) {
        if (recordingSide != null) return // already mid-recording on the other key
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(TAG, "RECORD_AUDIO not granted yet — requesting")
            pendingRecordSide = side
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), RECORD_AUDIO_REQUEST_CODE)
            return
        }
        if (voiceHelper.startRecording()) {
            recordingSide = side
            val label = if (side == RecordSide.ENGLISH) "English" else SettingsHelper.getOtherLanguage(this)
            listeningIndicator.text = "Listening ($label)…"
            listeningIndicator.visibility = View.VISIBLE
        } else {
            Log.e(TAG, "voiceHelper.startRecording() returned false — mic busy or MediaRecorder setup failed")
            Toast.makeText(this, "Couldn't start the microphone — see README", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        when (requestCode) {
            RECORD_AUDIO_REQUEST_CODE -> {
                Log.i(TAG, "RECORD_AUDIO permission result: granted=$granted")
                if (granted) {
                    Toast.makeText(this, "Microphone enabled — hold the softkey again to talk", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(
                        this,
                        "Microphone permission denied — enable it manually: Settings > Apps > TurboTranslate > Permissions",
                        Toast.LENGTH_LONG
                    ).show()
                }
                pendingRecordSide = null
            }
            STORAGE_PERMISSION_REQUEST_CODE -> {
                Log.i(TAG, "storage permission result: granted=$granted")
                if (granted) {
                    GroqConfig.ensureKeyFileExists()
                    if (GroqConfig.getApiKey().isBlank()) showMissingKeyToast()
                } else {
                    Toast.makeText(
                        this,
                        "Storage permission denied — TurboTranslate can't read your Groq key. " +
                            "Enable it: Settings > Apps > TurboTranslate > Permissions",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            CAMERA_PERMISSION_REQUEST_CODE -> {
                Log.i(TAG, "CAMERA permission result: granted=$granted")
                if (granted) {
                    startPhotoCapture()
                } else {
                    Toast.makeText(
                        this,
                        "Camera permission denied — enable it manually: Settings > Apps > TurboTranslate > Permissions",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun stopRecordingAndTranslate(side: RecordSide) {
        if (recordingSide != side) return // released a key that wasn't the one recording
        recordingSide = null
        listeningIndicator.text = "Transcribing…"

        val otherLanguage = SettingsHelper.getOtherLanguage(this)
        val targetLanguage = if (side == RecordSide.ENGLISH) otherLanguage else "English"

        voiceHelper.stopRecordingAndTranscribe(
            onResult = { originalText ->
                if (originalText.isEmpty()) {
                    listeningIndicator.visibility = View.GONE
                    return@stopRecordingAndTranscribe
                }
                listeningIndicator.text = "Translating…"
                GroqTranslateHelper.translate(
                    text = originalText,
                    targetLanguage = targetLanguage,
                    onResult = { translated ->
                        listeningIndicator.visibility = View.GONE
                        adapter.addEntry(
                            TranslatorEntry(
                                fromEnglish = side == RecordSide.ENGLISH,
                                originalText = originalText,
                                translatedText = translated,
                                otherLanguageName = otherLanguage
                            )
                        )
                        onEntryAdded()
                    },
                    onError = { message ->
                        listeningIndicator.visibility = View.GONE
                        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                    }
                )
            },
            onError = { message ->
                listeningIndicator.visibility = View.GONE
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
        )
    }

    /** [sawDown] tells us which mode this press was in. If the matching
     *  DOWN arrived, this UP is a normal hold-release — stop and process
     *  whatever's recording. If it didn't (this key's DOWN never reached
     *  the app at all), fall back to tap-toggle: this UP either starts a
     *  fresh recording (first tap) or stops the one already running from
     *  the previous tap (second tap). */
    private fun handleSoftKeyUp(side: RecordSide, sawDown: Boolean) {
        if (sawDown) {
            stopRecordingAndTranslate(side)
            return
        }
        if (recordingSide == null) {
            startRecording(side)
        } else if (recordingSide == side) {
            stopRecordingAndTranslate(side)
        }
        // else: recording is running for the *other* side — ignore this
        // tap rather than interrupt it.
    }

    /** Called after a new entry lands in the transcript. Jumps the
     *  selection to it only if the user hadn't navigated away to read
     *  older messages — otherwise leaves their place alone. */
    private fun onEntryAdded() {
        if (adapter.itemCount == 0) return
        if (followLatest) {
            selectedPosition = adapter.itemCount - 1
            adapter.setSelectedPosition(selectedPosition)
            transcriptList.scrollToPosition(selectedPosition!!)
        }
        SettingsHelper.saveTranscript(this, adapter.currentItems())
    }

    private fun scrollStepPx(): Int = (120 * resources.displayMetrics.density).toInt()

    /** Moves selection in [direction] (-1 = up/older, +1 = down/newer).
     *  If the *currently selected* message doesn't fully fit the screen,
     *  this scrolls further into it a step at a time instead of jumping
     *  straight to the next message — so a long translation can actually
     *  be read. Only once its far edge comes into view does the next
     *  press advance to the next message.
     *
     *  This checks the currently-displayed view's bounds rather than
     *  (as FlipText's ConversationActivity does) the next message's —
     *  the current one is guaranteed to be attached since it's on
     *  screen, whereas the next one often isn't yet (RecyclerView
     *  doesn't keep off-screen neighbors around by default), which is
     *  what made that approach unreliable here. */
    private fun stepSelection(direction: Int): Boolean {
        if (adapter.itemCount == 0) return false
        val current = selectedPosition ?: (adapter.itemCount - 1)
        val lm = transcriptList.layoutManager as? LinearLayoutManager
        val currentView = lm?.findViewByPosition(current)

        if (currentView != null) {
            if (direction > 0 && currentView.bottom > transcriptList.height) {
                transcriptList.scrollBy(0, scrollStepPx())
                return true
            }
            if (direction < 0 && currentView.top < 0) {
                transcriptList.scrollBy(0, -scrollStepPx())
                return true
            }
        }

        val target = current + direction
        if (target < 0 || target > adapter.itemCount - 1) return false
        selectedPosition = target
        adapter.setSelectedPosition(target)
        transcriptList.scrollToPosition(target)
        followLatest = target == adapter.itemCount - 1
        return true
    }

    private fun copySelectedToClipboard() {
        val pos = selectedPosition ?: return
        val entry = adapter.getEntry(pos) ?: return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("translation", entry.translatedText))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    private fun startPhotoCapture() {
        if (recordingSide != null) return // don't stack a camera trip on top of an active recording
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST_CODE)
            return
        }
        val dir = File(cacheDir, "camera").apply { mkdirs() }
        val file = File(dir, "translate_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        pendingPhotoUri = uri
        val captureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
        }
        if (captureIntent.resolveActivity(packageManager) != null) {
            @Suppress("DEPRECATION")
            startActivityForResult(captureIntent, CAMERA_CAPTURE_REQUEST_CODE)
        } else {
            Toast.makeText(this, "No camera app available", Toast.LENGTH_SHORT).show()
            pendingPhotoUri = null
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != CAMERA_CAPTURE_REQUEST_CODE) return
        val uri = pendingPhotoUri
        pendingPhotoUri = null
        if (resultCode == RESULT_OK && uri != null) {
            processPhoto(uri)
        }
    }

    private fun processPhoto(uri: Uri) {
        val file = uri.path?.let { File(it) }?.takeIf { it.exists() }
            ?: run {
                // FileProvider URIs from ACTION_IMAGE_CAPTURE resolve straight
                // to the file we created in startPhotoCapture, but fall back
                // to copying via the resolver if that ever isn't the case.
                val tmp = File(cacheDir, "camera/translate_copy_${System.currentTimeMillis()}.jpg")
                try {
                    contentResolver.openInputStream(uri)?.use { input ->
                        tmp.outputStream().use { output -> input.copyTo(output) }
                    }
                    tmp
                } catch (e: Exception) {
                    Log.e(TAG, "couldn't read photo", e)
                    null
                }
            }
        if (file == null) {
            Toast.makeText(this, "Couldn't read that photo", Toast.LENGTH_SHORT).show()
            return
        }

        listeningIndicator.text = "Reading photo…"
        listeningIndicator.visibility = View.VISIBLE

        val otherLanguage = SettingsHelper.getOtherLanguage(this)
        GroqVisionHelper.extractAndTranslate(
            imageFile = file,
            otherLanguage = otherLanguage,
            onResult = { result ->
                listeningIndicator.visibility = View.GONE
                adapter.addEntry(
                    TranslatorEntry(
                        fromEnglish = result.detectedLanguage.equals("English", ignoreCase = true),
                        originalText = result.extractedText,
                        translatedText = result.translatedText,
                        otherLanguageName = result.detectedLanguage,
                        isPhoto = true
                    )
                )
                onEntryAdded()
            },
            onError = { message ->
                listeningIndicator.visibility = View.GONE
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
        )
    }

    /** Options menu on `0`/Menu — a plain setItems dialog, which the
     *  D-pad drives as is. The right soft key would be the usual Options
     *  key on these phones, but here it's English push-to-talk. */
    private fun showOptionsMenu() {
        if (recordingSide != null) return
        val items = arrayOf(
            "Change language (*)",
            "Take photo (#)",
            "Check for updates (v${UpdateChecker.installedVersion(this)})"
        )
        AlertDialog.Builder(this)
            .setTitle("Options")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> openLanguagePicker()
                    1 -> startPhotoCapture()
                    2 -> checkForUpdateNow()
                }
            }
            .show()
    }

    private fun openLanguagePicker() {
        if (recordingSide != null) return
        startActivity(Intent(this, LanguagePickerActivity::class.java))
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            Log.i(TAG, "key DOWN: keyCode=${event.keyCode} repeatCount=${event.repeatCount}")
            when (event.keyCode) {
                // KEYCODE_7 / KEYCODE_9 mirror the left/right softkeys —
                // lets this work on other D-pad phones whose softkeys
                // send different (or no) codes.
                KeyEvent.KEYCODE_SOFT_RIGHT, KeyEvent.KEYCODE_9 -> {
                    if (event.repeatCount == 0) {
                        rightKeySeenDown = true
                        startRecording(RecordSide.ENGLISH)
                    }
                    return true
                }
                KeyEvent.KEYCODE_SOFT_LEFT, KeyEvent.KEYCODE_7 -> {
                    if (event.repeatCount == 0) {
                        leftKeySeenDown = true
                        startRecording(RecordSide.OTHER)
                    }
                    return true
                }
                KeyEvent.KEYCODE_STAR -> {
                    openLanguagePicker()
                    return true
                }
                KeyEvent.KEYCODE_POUND -> {
                    startPhotoCapture()
                    return true
                }
                // Opened on UP (below) so the release doesn't land in
                // the dialog and pick its focused item.
                KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_MENU -> {
                    if (event.repeatCount == 0) optionsKeySeenDown = true
                    return true
                }
                // Moves the highlighted message instead of raw-scrolling —
                // this is what center/OK below acts on.
                KeyEvent.KEYCODE_DPAD_UP -> { stepSelection(-1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { stepSelection(1); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    copySelectedToClipboard()
                    return true
                }
                KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_BACK -> {
                    if (recordingSide == null) {
                        finish()
                    } else {
                        voiceHelper.cancelRecording()
                        recordingSide = null
                        listeningIndicator.visibility = View.GONE
                    }
                    return true
                }
            }
        } else if (event.action == KeyEvent.ACTION_UP) {
            Log.i(TAG, "key UP: keyCode=${event.keyCode}")
            when (event.keyCode) {
                KeyEvent.KEYCODE_SOFT_RIGHT, KeyEvent.KEYCODE_9 -> {
                    handleSoftKeyUp(RecordSide.ENGLISH, sawDown = rightKeySeenDown)
                    rightKeySeenDown = false
                    return true
                }
                KeyEvent.KEYCODE_SOFT_LEFT, KeyEvent.KEYCODE_7 -> {
                    handleSoftKeyUp(RecordSide.OTHER, sawDown = leftKeySeenDown)
                    leftKeySeenDown = false
                    return true
                }
                KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_MENU -> {
                    if (optionsKeySeenDown && !event.isCanceled) showOptionsMenu()
                    optionsKeySeenDown = false
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
