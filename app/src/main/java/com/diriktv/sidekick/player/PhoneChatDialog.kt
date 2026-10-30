package com.diriktv.sidekick.player

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.ComponentDialog
import com.diriktv.sidekick.R
import com.diriktv.sidekick.data.KickApi
import com.diriktv.sidekick.data.PhoneChat
import com.diriktv.sidekick.ui.dp
import com.diriktv.sidekick.ui.qrBitmap

/** The QR code for chatting from a phone. */
object PhoneChatDialog {
    fun show(act: PlayerActivity) {
        val root = LayoutInflater.from(act).inflate(R.layout.dialog_phone_chat, null)
        val url = PhoneChat.start(act)
        val qr = root.findViewById<ImageView>(R.id.qr)
        if (url == null) {
            qr.setImageResource(R.drawable.ic_info)
            root.findViewById<TextView>(R.id.body).text = "Chatting from your phone needs the TV to be on Wi-Fi or Ethernet."
        } else {
            qr.setImageBitmap(qrBitmap(url, act.dp(200)))
            root.findViewById<TextView>(R.id.url).text = url.substringBefore("/?")
        }
        root.findViewById<View>(R.id.warn).visibility = if (KickApi.authToken == null) View.VISIBLE else View.GONE
        val dialog = ComponentDialog(act, R.style.KickSheet)
        dialog.setContentView(root)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog.setOnKeyListener { d, keyCode, event ->
            // Menu or OK also close it, like the other popups.
            if (keyCode == android.view.KeyEvent.KEYCODE_MENU || keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER) {
                if (event.action == android.view.KeyEvent.ACTION_UP) d.dismiss()
                true
            } else false
        }
        dialog.setOnDismissListener { if (act.controls.up) act.controls.scheduleHide() }
        act.controls.cancelHide()
        dialog.show()
        val card = root.findViewById<View>(R.id.card)
        card.alpha = 0f
        card.scaleX = 0.96f
        card.scaleY = 0.96f
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).start()
    }
}
