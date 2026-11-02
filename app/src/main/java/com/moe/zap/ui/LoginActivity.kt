package com.moe.zap.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import com.moe.zap.R
import com.moe.zap.core.Account
import com.moe.zap.data.LoginError
import com.moe.zap.data.Repo

class LoginActivity : Activity() {
    private lateinit var server: EditText
    private lateinit var username: EditText
    private lateinit var password: EditText
    private lateinit var error: TextView
    private lateinit var signIn: TextView
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        server = findViewById(R.id.server)
        username = findViewById(R.id.username)
        password = findViewById(R.id.password)
        error = findViewById(R.id.error)
        signIn = findViewById(R.id.signIn)
        signIn.setOnClickListener { submit() }
        password.setOnEditorActionListener { _, action, event ->
            if (action == EditorInfo.IME_ACTION_DONE || event?.keyCode == android.view.KeyEvent.KEYCODE_ENTER) {
                signIn.requestFocus()
                submit()
                true
            } else {
                false
            }
        }
        server.requestFocus()
    }

    private fun show(message: String) {
        error.text = message
        error.visibility = View.VISIBLE
    }

    private fun submit() {
        if (busy) return
        val account = Account(server.text.toString().trim(), username.text.toString().trim(), password.text.toString())
        if (account.server.isEmpty() || account.username.isEmpty() || account.password.isEmpty()) {
            show("Please fill in all three fields.")
            return
        }
        busy = true
        error.visibility = View.GONE
        getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(window.decorView.windowToken, 0)
        signIn.text = "Signing in…"
        Repo.login(account) { result ->
            busy = false
            signIn.text = "Sign in"
            result.onSuccess {
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }.onFailure { e ->
                if ((e as? LoginError)?.reason == LoginError.Reason.WRONG_LOGIN) password.requestFocus()
                show(
                    when ((e as? LoginError)?.reason) {
                        LoginError.Reason.WRONG_LOGIN -> "That username or password was not accepted."
                        LoginError.Reason.EXPIRED -> "This subscription is not active. Check with your provider."
                        LoginError.Reason.NOT_XTREAM -> "That address answered, but it does not look like an IPTV server."
                        else -> "Could not reach the server. Check the address and your connection."
                    }
                )
            }
        }
    }
}
