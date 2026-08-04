package com.hiklocal

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.hiklocal.databinding.ActivityLoginBinding
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var b: ActivityLoginBinding
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs(this)

        b.hostInput.setText(prefs.host)
        b.userInput.setText(prefs.user)
        b.rememberBox.isChecked = prefs.remember

        val hasStoredPassword = prefs.remember && prefs.password.isNotEmpty()
        if (hasStoredPassword) {
            b.passwordInput.setText(prefs.password)
            // Un mot de passe déjà enregistré ne doit pas pouvoir être révélé :
            // quiconque prend l'appareil en main pourrait le lire en clair.
            // L'œil de révélation n'a de sens que lors d'une saisie, pour
            // vérifier ce que l'on tape.
            b.passwordLayout.endIconMode = TextInputLayout.END_ICON_NONE
        } else {
            b.passwordLayout.endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
        }

        // Dès que l'utilisateur modifie le champ, il saisit à nouveau : l'œil
        // redevient utile.
        b.passwordInput.setOnFocusChangeListener { _, focused ->
            if (focused && hasStoredPassword) {
                b.passwordInput.setText("")
                b.passwordLayout.endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
            }
        }

        b.loginButton.setOnClickListener { connect() }
    }

    private fun connect() {
        val host = b.hostInput.text?.toString()?.trim().orEmpty()
        val user = b.userInput.text?.toString()?.trim().orEmpty()
        val password = b.passwordInput.text?.toString().orEmpty()

        if (host.isEmpty() || user.isEmpty() || password.isEmpty()) {
            showError(getString(R.string.err_credentials))
            return
        }

        setBusy(true)
        lifecycleScope.launch {
            val api = HikApi(host, user, password)
            when (val r = api.checkConnection()) {
                is ApiResult.Ok -> {
                    prefs.host = host
                    prefs.user = user
                    prefs.remember = b.rememberBox.isChecked
                    // Mot de passe conservé seulement si l'utilisateur le demande.
                    if (b.rememberBox.isChecked) prefs.password = password
                    else prefs.clearCredentials()

                    Session.api = api
                    startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                    finish()
                }
                is ApiResult.Err -> {
                    setBusy(false)
                    showError(
                        when (r.kind) {
                            ErrorKind.CREDENTIALS -> getString(R.string.err_credentials)
                            ErrorKind.UNREACHABLE -> getString(R.string.err_unreachable)
                            ErrorKind.DEVICE ->
                                getString(R.string.err_unreachable) + " (" + r.detail + ")"
                        }
                    )
                }
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        b.loginButton.isEnabled = !busy
        b.loginButton.text =
            getString(if (busy) R.string.login_checking else R.string.login_action)
    }

    private fun showError(message: String) {
        b.errorText.text = message
        b.errorText.visibility = View.VISIBLE
    }
}

/**
 * La connexion active, partagée entre les écrans. Elle vit en mémoire
 * uniquement : fermer l'application oblige à se reconnecter.
 */
object Session {
    var api: HikApi? = null
    var cameras: List<Camera> = emptyList()
}
