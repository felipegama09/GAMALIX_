package br.gamaflix

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class LoginActivity : AppCompatActivity() {

    private var xtream = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Login salvo: vai direto para a lista.
        Prefs.buildSource(this)?.let {
            Store.source = it
            goMain()
            return
        }

        setContentView(R.layout.activity_login)
        val tabX = findViewById<TextView>(R.id.tabXtream)
        val tabM = findViewById<TextView>(R.id.tabM3u)
        val etServer = findViewById<EditText>(R.id.etServer)
        val etUser = findViewById<EditText>(R.id.etUser)
        val etPass = findViewById<EditText>(R.id.etPass)
        val btn = findViewById<TextView>(R.id.btnEnter)

        fun mode(x: Boolean) {
            xtream = x
            tabX.isSelected = x
            tabM.isSelected = !x
            val vis = if (x) View.VISIBLE else View.GONE
            etUser.visibility = vis
            etPass.visibility = vis
            etServer.hint = if (x) "Servidor (ex.: http://exemplo.com:8080)" else "Link da lista M3U"
        }

        fun enter() {
            val s = etServer.text.toString().trim()
            val u = etUser.text.toString().trim()
            val p = etPass.text.toString().trim()
            if (s.isEmpty() || (xtream && (u.isEmpty() || p.isEmpty()))) {
                Toast.makeText(this, "Preencha todos os campos", Toast.LENGTH_SHORT).show()
                return
            }
            Prefs.save(this, if (xtream) "xtream" else "m3u", s, u, p)
            Store.reset()
            Store.source = Prefs.buildSource(this)
            goMain()
        }

        tabX.setOnClickListener { mode(true) }
        tabM.setOnClickListener { mode(false) }
        btn.setOnClickListener { enter() }
        etPass.setOnEditorActionListener { _, _, _ -> enter(); true }
        etServer.setOnEditorActionListener { _, _, _ ->
            if (!xtream) enter() else etUser.requestFocus()
            true
        }
        mode(true)
    }

    private fun goMain() {
        startActivity(Intent(this, HomeActivity::class.java))
        finish()
    }
}
