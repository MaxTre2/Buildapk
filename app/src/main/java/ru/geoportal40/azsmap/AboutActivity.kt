package ru.geoportal40.azsmap

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        val siteUrl = getString(R.string.about_site_url)
        val emailAddress = getString(R.string.about_email_address)

        findViewById<android.view.View>(R.id.siteRow).setOnClickListener {
            openUrl(siteUrl)
        }

        findViewById<android.view.View>(R.id.emailRow).setOnClickListener {
            openEmail(emailAddress)
        }

        val sberCard = findViewById<TextView>(R.id.sberCardText).text.toString()
        findViewById<android.view.View>(R.id.copySberButton).setOnClickListener {
            copyToClipboard(getString(R.string.about_sber), sberCard)
        }

        val yandexCard = findViewById<TextView>(R.id.yandexCardText).text.toString()
        findViewById<android.view.View>(R.id.copyYandexButton).setOnClickListener {
            copyToClipboard(getString(R.string.about_yandex), yandexCard)
        }

        findViewById<TextView>(R.id.versionText).text =
            getString(R.string.app_version_label, BuildConfig.VERSION_NAME)

        findViewById<android.view.View>(R.id.closeButton).setOnClickListener {
            finish()
        }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(this, "Не удалось открыть ссылку", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openEmail(address: String) {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:$address")
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "Не найдено почтовое приложение", Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, R.string.copied_toast, Toast.LENGTH_SHORT).show()
    }
}