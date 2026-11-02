package com.moe.zap.ui

import android.view.View
import android.widget.TextView
import com.moe.zap.data.Lock
import com.moe.zap.data.Repo
import com.moe.zap.data.Syncer
import com.moe.zap.R
import java.text.DateFormat
import java.util.Date

class SettingsPane(
    private val activity: MainActivity,
    val root: View,
    private val onLoggedOut: () -> Unit,
) {
    private val accountText: TextView = root.findViewById(R.id.settingsAccount)
    private val libraryText: TextView = root.findViewById(R.id.settingsLibrary)
    private val refreshButton: TextView = root.findViewById(R.id.settingsRefresh)
    private val logoutButton: TextView = root.findViewById(R.id.settingsLogout)
    private var confirmLogout = false

    init {
        root.findViewById<View>(R.id.settingsManage).setOnClickListener {
            activity.startActivity(android.content.Intent(activity, ManageCategoriesActivity::class.java))
        }
        root.findViewById<View>(R.id.settingsPin).setOnClickListener {
            if (Lock.hasPin()) {
                PinDialog.verify(activity, "Enter your current PIN") { PinDialog.create(activity) {} }
            } else {
                PinDialog.create(activity) {}
            }
        }
        refreshButton.setOnClickListener {
            if (!Syncer.running) {
                Syncer.onProgress = { p ->
                    libraryText.text = if (p.items > 0) "${p.step}: ${"%,d".format(p.items)} titles" else p.step
                }
                Syncer.onFinished = { r ->
                    Syncer.onProgress = null
                    refresh()
                    if (r.isFailure) libraryText.text = "Refresh failed. Your library was kept as it was."
                }
                libraryText.text = "Connecting…"
                Syncer.start()
            }
        }
        // Signing out erases the library and favorites, so it takes a second, deliberate press.
        logoutButton.setOnClickListener {
            if (confirmLogout) {
                logoutButton.text = "Signing out…"
                Repo.logout(onLoggedOut)
            } else {
                confirmLogout = true
                logoutButton.text = "Press again to sign out"
            }
        }
        logoutButton.setOnFocusChangeListener { _, has ->
            if (!has && confirmLogout) {
                confirmLogout = false
                logoutButton.text = "Sign out"
            }
        }
    }

    fun focus() {
        refreshButton.requestFocus()
    }

    fun refresh() {
        Repo.bg({ Repo.account()?.username.orEmpty() }) { r ->
            val p = Repo.prefs
            val expires = p.getLong("expires", 0L)
            val date = DateFormat.getDateInstance(DateFormat.LONG)
            accountText.text = buildString {
                append("Signed in as ").append(r.getOrDefault(""))
                val status = p.getString("status", "").orEmpty()
                if (status.isNotEmpty()) append("\nStatus: ").append(status)
                if (expires > 0) append("\nSubscription ends ").append(date.format(Date(expires * 1000)))
                val max = p.getInt("maxConnections", 0)
                if (max > 0) append("\nScreens allowed at once: ").append(max)
            }
            if (!Syncer.running) {
                val last = Syncer.lastSyncAt()
                libraryText.text = if (last > 0) {
                    "Library updated " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(last))
                } else {
                    "Library not downloaded yet"
                }
            }
        }
    }
}
