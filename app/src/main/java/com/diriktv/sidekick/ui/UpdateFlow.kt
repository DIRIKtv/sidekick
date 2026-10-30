package com.diriktv.sidekick.ui

import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.diriktv.sidekick.R
import com.diriktv.sidekick.data.ReleaseNotes
import com.diriktv.sidekick.data.Updater
import kotlinx.coroutines.launch
import java.io.File

/** The "update available" panel, then a download card with a live meter, then the installer. */
class UpdateFlow(private val act: FragmentActivity, private val release: Updater.Release) {
    private val sheet = OptionSheet(act)
    private var apk: File? = null
    private var error: String? = null

    fun show() {
        sheet.show(OptionSheet.Page("Update available", eyebrow = "Sidekick ${release.version}", subtitle = notes()) {
            listOf(
                OptionSheet.Item("Update now", sub = sizeLabel(), icon = R.drawable.ic_download) { startDownload() },
                OptionSheet.Item("Not now", icon = R.drawable.ic_close) { it.dismiss() },
                OptionSheet.Item("Skip this version", icon = R.drawable.ic_info) {
                    Updater.skip(act, release.version); it.dismiss()
                },
            )
        })
    }

    private fun notes(): String = ReleaseNotes.forTv(release.notes)

    private fun sizeLabel() = if (release.sizeBytes > 0) String.format("%.1f MB", release.sizeBytes / 1_048_576f) else null

    private var dialog: androidx.activity.ComponentDialog? = null
    private var job: kotlinx.coroutines.Job? = null

    /** Swaps the menu for the download card: a meter, the size and time left, then install. */
    private fun startDownload() {
        sheet.dismiss()
        val root = android.view.LayoutInflater.from(act).inflate(R.layout.dialog_update, null)
        val d = androidx.activity.ComponentDialog(act, R.style.KickSheet)
        d.setContentView(root)
        d.window?.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
        d.setOnDismissListener { job?.cancel() }
        dialog = d
        d.show()
        val card = root.findViewById<android.view.View>(R.id.card)
        card.alpha = 0f
        card.translationY = act.dp(16).toFloat()
        card.animate().alpha(1f).translationY(0f).setDuration(260)
            .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        download(root)
    }

    private fun download(root: android.view.View) {
        apk = null
        error = null
        val percent = root.findViewById<TextView>(R.id.percent)
        val meter = root.findViewById<DownloadMeter>(R.id.meter).apply {
            reset()
            // The number counts up with the bar as drawn, so it never runs ahead of it.
            onDisplay = { percent.text = (it * 100).toInt().toString() }
        }
        val title = root.findViewById<TextView>(R.id.title)
        val status = root.findViewById<TextView>(R.id.status)
        title.text = "Updating to ${release.version}"
        status.text = "Starting download…"
        setButtons(root, listOf("Cancel" to { dialog?.dismiss() }))
        val samples = ArrayDeque<Pair<Long, Long>>()
        job = act.lifecycleScope.launch {
            try {
                apk = Updater.download(act, release) { done, total ->
                    val f = if (total > 0) done.toFloat() / total else 0f
                    meter.setProgress(f)
                    val now = android.os.SystemClock.uptimeMillis()
                    samples.addLast(now to done)
                    while (samples.size > 2 && now - samples.first().first > 4_000) samples.removeFirst()
                    status.text = progressLine(done, total, samples)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Download failed"
            }
            val err = error
            if (err != null) {
                meter.failed = true
                title.text = "Download didn't finish"
                status.text = err
                setButtons(root, listOf("Try again" to { download(root) }, "Cancel" to { dialog?.dismiss() }))
                return@launch
            }
            meter.complete()
            title.text = "Ready to install"
            status.text = "Sidekick ${release.version} is downloaded."
            if (Updater.canInstall(act)) {
                setButtons(root, listOf("Install now" to { install() }))
                // Let the check finish drawing, then hand over to the installer.
                kotlinx.coroutines.delay(1_100)
                install()
            } else {
                status.text = "Allow Sidekick to install updates, then choose Install."
                setButtons(root, listOf("Allow installs" to { allowInstalls() }, "Install" to { install() }))
            }
        }
    }

    /** "1.2 of 2.4 MB • 3s left", with the speed taken over the last few seconds. */
    private fun progressLine(done: Long, total: Long, samples: ArrayDeque<Pair<Long, Long>>): String {
        fun mb(b: Long) = String.format(java.util.Locale.US, "%.1f", b / 1_048_576f)
        if (total <= 0) return "${mb(done)} MB"
        val (t0, b0) = samples.first()
        val (t1, b1) = samples.last()
        val rate = if (t1 - t0 >= 1_000 && b1 > b0) (b1 - b0) * 1000f / (t1 - t0) else 0f
        val left = if (rate > 0) ((total - done) / rate).toInt() else -1
        val eta = when {
            left < 0 -> ""
            left < 2 -> " • almost done"
            left < 60 -> " • ${left}s left"
            left < 600 -> " • ${left / 60}m ${left % 60}s left"
            else -> " • this could take a while"
        }
        return "${mb(done)} of ${mb(total)} MB$eta"
    }

    private fun setButtons(root: android.view.View, buttons: List<Pair<String, () -> Unit>>) {
        val row = root.findViewById<android.widget.LinearLayout>(R.id.buttons)
        row.removeAllViews()
        buttons.forEachIndexed { i, (label, action) ->
            row.addView(TextView(act, null, 0, R.style.Pill).apply {
                text = label
                font(AppFonts.Weight.MEDIUM)
                setOnClickListener { action() }
            }, android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, act.dp(40)).apply {
                marginEnd = act.dp(12)
            })
            if (i == 0) row.getChildAt(0).post { row.getChildAt(0)?.requestFocus() }
        }
    }

    private fun allowInstalls() {
        if (!Updater.openInstallPermission(act)) {
            Toast.makeText(
                act,
                "Open Settings → My Fire TV → Developer options → Install unknown apps, and turn on Sidekick.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun install() {
        val file = apk ?: return
        if (!Updater.canInstall(act)) {
            allowInstalls()
            return
        }
        dialog?.setOnDismissListener(null)
        dialog?.dismiss()
        runCatching { Updater.install(act, file) }.onFailure {
            Toast.makeText(act, "Couldn't open the installer: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }
}
