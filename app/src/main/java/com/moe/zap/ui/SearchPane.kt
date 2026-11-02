package com.moe.zap.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.GridLayout
import android.widget.TextView
import android.widget.Toast
import androidx.leanback.widget.VerticalGridView
import com.moe.zap.R
import com.moe.zap.core.Kind
import com.moe.zap.data.Item
import com.moe.zap.data.Repo

/** A keyboard built for a remote, always on screen, with results updating at every key press. */
class SearchPane(
    private val activity: MainActivity,
    val root: View,
    private val onOpen: (Item) -> Unit,
) {
    private val queryView: TextView = root.findViewById(R.id.query)
    private val keyboard: GridLayout = root.findViewById(R.id.keyboard)
    private val results: VerticalGridView = root.findViewById(R.id.results)
    private val hint: TextView = root.findViewById(R.id.searchHint)
    private val langKey: TextView = root.findViewById(R.id.keyLang)
    private val keys = ArrayList<TextView>()
    private var arabic = false
    private var query = StringBuilder()
    private var filter: Kind? = null
    private var recognizer: SpeechRecognizer? = null
    private val voiceKey: TextView = root.findViewById(R.id.keyVoice)

    private val adapter = ResultAdapter(onOpen) { item ->
        val on = Repo.toggleFavorite(item.kind, item.id)
        Toast.makeText(activity, if (on) "Added to favorites" else "Removed from favorites", Toast.LENGTH_SHORT).show()
    }

    init {
        results.tune()
        results.setVerticalSpacing(activity.dp(2))
        results.adapter = adapter
        val inflater = LayoutInflater.from(activity)
        val gap = activity.dp(4)
        for (i in 0 until LATIN.length) {
            val key = inflater.inflate(R.layout.key, keyboard, false) as TextView
            val lp = GridLayout.LayoutParams(key.layoutParams)
            lp.width = 0
            lp.columnSpec = GridLayout.spec(i % COLUMNS, 1f)
            lp.rowSpec = GridLayout.spec(i / COLUMNS)
            lp.setMargins(if (i % COLUMNS == 0) 0 else gap, if (i < COLUMNS) 0 else gap, 0, 0)
            key.layoutParams = lp
            key.setOnClickListener { type(key.text.toString()) }
            keyboard.addView(key)
            keys += key
        }
        showKeys()
        root.findViewById<View>(R.id.keySpace).setOnClickListener { if (query.isNotEmpty() && query.last() != ' ') type(" ") }
        root.findViewById<View>(R.id.keyDelete).setOnClickListener { deleteOne() }
        root.findViewById<View>(R.id.keyClear).setOnClickListener { setQuery("") }
        langKey.setOnClickListener {
            arabic = !arabic
            showKeys()
        }
        voiceKey.setOnClickListener { startVoice() }
        val filters = listOf(
            R.id.filterAll to null, R.id.filterLive to Kind.LIVE,
            R.id.filterMovies to Kind.MOVIE, R.id.filterSeries to Kind.SERIES,
        ).map { (id, kind) -> root.findViewById<TextView>(id) to kind }
        filters.first().first.isActivated = true
        for ((view, kind) in filters) {
            // Filtering as focus lands on a chip: no press needed, and the results change right away.
            view.setOnFocusChangeListener { _, has ->
                if (has && filter != kind) {
                    filter = kind
                    filters.forEach { (v, k) -> v.isActivated = k == kind }
                    setQuery(query.toString())
                }
            }
        }
    }

    private fun showKeys() {
        val set = if (arabic) ARABIC else LATIN
        keys.forEachIndexed { i, key ->
            val c = set.getOrNull(i)
            key.text = c?.toString().orEmpty()
            // On the shorter Arabic layout, unused keys simply are not there to land on.
            key.visibility = if (c == null) View.INVISIBLE else View.VISIBLE
        }
        langKey.text = if (arabic) "ABC" else "عربي"
    }

    fun focus() {
        keys.first().requestFocus()
    }

    /** Lets number keys and a connected keyboard type directly. */
    fun onKey(e: KeyEvent): Boolean {
        if (e.keyCode == KeyEvent.KEYCODE_DEL) {
            deleteOne()
            return true
        }
        val c = e.unicodeChar
        if (c > 32 && (Character.isLetterOrDigit(c))) {
            type(String(Character.toChars(c)))
            return true
        }
        return false
    }

    private fun startVoice() {
        if (recognizer != null) {
            stopVoice()
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(activity)) {
            Toast.makeText(activity, "Voice search isn't available on this TV", Toast.LENGTH_SHORT).show()
            return
        }
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            listen()
        } else {
            activity.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), VOICE_REQUEST)
        }
    }

    fun onPermissionResult(requestCode: Int, grants: IntArray) {
        if (requestCode != VOICE_REQUEST) return
        if (grants.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            listen()
        } else {
            Toast.makeText(activity, "Voice search needs the microphone", Toast.LENGTH_SHORT).show()
        }
    }

    // Listens right here, rather than opening the system's voice screen, which never handed back what was said.
    private fun listen() {
        val speech = SpeechRecognizer.createSpeechRecognizer(activity)
        recognizer = speech
        speech.setRecognitionListener(object : RecognitionListener {
            private fun show(results: Bundle?) {
                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.takeIf { it.isNotBlank() }?.let(::setQuery)
            }

            override fun onPartialResults(results: Bundle?) = show(results)

            override fun onResults(results: Bundle?) {
                show(results)
                stopVoice()
            }

            override fun onError(error: Int) {
                // Services report a late error after their results too; by then this listener is already finished.
                if (recognizer !== speech) return
                android.util.Log.w("ZapVoice", "recognizer error $error")
                stopVoice()
                val message = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that. Try again."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Voice search needs the internet"
                    else -> "Voice search didn't work (error $error)"
                }
                Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
            }

            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, activity.packageName)
        // The keyboard's language is the best hint at what the viewer is about to say.
        if (arabic) intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-EG")
        voiceKey.text = "🎤  Listening… press to stop"
        speech.startListening(intent)
    }

    fun stopVoice() {
        val speech = recognizer ?: return
        recognizer = null
        speech.destroy()
        voiceKey.text = "🎤  Voice search"
    }

    /** Back from results or filters returns to the keyboard. False means the menu should take focus. */
    fun handleBack(): Boolean {
        if (recognizer != null) {
            stopVoice()
            return true
        }
        if (keyboard.hasFocus() || root.findViewById<View>(R.id.keySpace).parent.let { (it as View).hasFocus() } ||
            root.findViewById<View>(R.id.keyVoice).hasFocus()) {
            return false
        }
        focus()
        return true
    }

    private fun type(s: String) = setQuery(query.toString() + s)

    private fun deleteOne() {
        if (query.isNotEmpty()) setQuery(query.substring(0, query.length - 1))
    }

    private fun setQuery(q: String) {
        query = StringBuilder(q)
        queryView.text = q
        Repo.search(q, filter) { list, tookMs ->
            android.util.Log.w("ZapPerf", "search len=${q.length} results=${list.size} ms=$tookMs")
            adapter.submit(list)
            if (list.isNotEmpty()) results.selectedPosition = 0
            hint.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            hint.text = if (q.isBlank()) "Results appear as you type" else "No matches for “${q.trim()}”"
        }
    }

    private companion object {
        const val COLUMNS = 6
        const val VOICE_REQUEST = 41
        const val LATIN = "abcdefghijklmnopqrstuvwxyz1234567890"
        // Spelling variants (أ إ آ ة ى ؤ ئ) need no keys of their own: search treats them as their base letter.
        const val ARABIC = "ابتثجحخدذرزسشصضطظعغفقكلمنهوي"
    }
}
