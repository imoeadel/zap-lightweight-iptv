package com.moe.zap.ui

import android.os.LocaleList
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.GridLayout
import android.widget.TextView
import android.widget.Toast
import androidx.leanback.widget.VerticalGridView
import com.moe.zap.R
import com.moe.zap.core.Kind
import com.moe.zap.data.Item
import com.moe.zap.data.Repo
import java.util.Locale

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
    private val voiceField: EditText = root.findViewById(R.id.voiceField)
    private val voiceKey: View = root.findViewById(R.id.keyVoice)

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
        voiceKey.setOnClickListener { openVoiceField() }
        voiceField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (voiceField.hasFocus()) setQuery(s?.toString().orEmpty())
            }
        })
        voiceField.setOnEditorActionListener { _, _, _ ->
            finishVoiceField()
            true
        }
        // Posted so the field isn't hidden in the middle of focus moving away from it.
        voiceField.setOnFocusChangeListener { _, has ->
            if (!has) voiceField.post { if (!voiceField.hasFocus()) hideVoiceField() }
        }
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
        if (voiceField.hasFocus()) return false
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

    // On this TV only the system keyboard's mic hears the remote: recording from the app opens the built-in microphone,
    // which crashes the TV's audio service, and the system voice screen never hands its words back. So voice search is
    // a real text field: the keyboard's mic types into it, and results follow each word.
    private fun openVoiceField() {
        setQuery("")
        voiceField.setText("")
        // The keyboard's language is the best hint at what the viewer is about to say.
        voiceField.imeHintLocales = LocaleList(Locale(if (arabic) "ar" else "en"))
        queryView.visibility = View.GONE
        voiceField.visibility = View.VISIBLE
        voiceField.requestFocus()
        voiceField.post { keyboardService().showSoftInput(voiceField, 0) }
    }

    private fun finishVoiceField() {
        if (adapter.itemCount == 0 || !results.requestFocus()) voiceKey.requestFocus()
    }

    private fun hideVoiceField() {
        keyboardService().hideSoftInputFromWindow(voiceField.windowToken, 0)
        voiceField.visibility = View.GONE
        queryView.visibility = View.VISIBLE
    }

    private fun keyboardService() = activity.getSystemService(InputMethodManager::class.java)

    /** Back from results or filters returns to the keyboard. False means the menu should take focus. */
    fun handleBack(): Boolean {
        if (voiceField.hasFocus()) {
            voiceKey.requestFocus()
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
        const val LATIN = "abcdefghijklmnopqrstuvwxyz1234567890"
        // Spelling variants (أ إ آ ة ى ؤ ئ) need no keys of their own: search treats them as their base letter.
        const val ARABIC = "ابتثجحخدذرزسشصضطظعغفقكلمنهوي"
    }
}
