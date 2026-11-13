package com.diriktv.sidekick.ui

import android.app.Activity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.ComponentDialog
import com.diriktv.sidekick.R

/** A QR code with a title and a few words, for handing a link over to a phone (same look as phone chat). */
object QrDialog {
    fun show(act: Activity, title: String, body: String, url: String, shownUrl: String? = null, onDismiss: () -> Unit = {}): ComponentDialog {
        @android.annotation.SuppressLint("InflateParams") // a dialog's content: it has no parent yet
        val root = LayoutInflater.from(act).inflate(R.layout.dialog_phone_chat, null)
        root.findViewById<TextView>(R.id.title).text = title
        root.findViewById<TextView>(R.id.body).text = body
        root.findViewById<TextView>(R.id.url).apply { text = shownUrl.orEmpty(); setVisible(!shownUrl.isNullOrEmpty()) }
        root.findViewById<ImageView>(R.id.qr).apply {
            setImageBitmap(qrBitmap(url, act.dp(200)))
            contentDescription = "QR code: scan it with your phone"
        }
        val dialog = ComponentDialog(act, R.style.KickSheet)
        dialog.setContentView(root)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog.setOnKeyListener { d, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                if (event.action == KeyEvent.ACTION_UP) d.dismiss()
                true
            } else false
        }
        dialog.setOnDismissListener { onDismiss() }
        dialog.show()
        val card = root.findViewById<View>(R.id.card)
        card.alpha = 0f
        card.scaleX = 0.96f
        card.scaleY = 0.96f
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).start()
        return dialog
    }
}
