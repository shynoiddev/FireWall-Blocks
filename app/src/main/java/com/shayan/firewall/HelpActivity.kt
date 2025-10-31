package com.shayan.firewall

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar

class HelpActivity : AppCompatActivity() {

    private val bitcoinAddress = "15GVxMuexgbDapqDDSxSi6h7EDsN2d7wdr"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_help)

        val toolbar = findViewById<Toolbar>(R.id.toolbar_help)
        setSupportActionBar(toolbar)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        supportActionBar?.setDisplayShowTitleEnabled(true)
        supportActionBar?.title = getString(R.string.help_title)


        // Link to Shizuku GitHub page
        val shizukuLink = findViewById<TextView>(R.id.text_shizuku_link)
        shizukuLink.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.help_shizuku_link)))
            startActivity(intent)
        }

        // Donate Button
        val donateButton = findViewById<Button>(R.id.button_donate)
        donateButton.setOnClickListener {
            copyToClipboard(bitcoinAddress)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // android.R.id.home is the ID for the "Up" button
        if (item.itemId == android.R.id.home) {
            finish() // Closes this activity and returns to MainActivity
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Bitcoin Address", text)
        clipboard.setPrimaryClip(clip)

        Toast.makeText(this, getString(R.string.help_donate_toast), Toast.LENGTH_SHORT).show()
    }
}

