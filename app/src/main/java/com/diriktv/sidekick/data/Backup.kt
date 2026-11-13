package com.diriktv.sidekick.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.Socket
import java.security.SecureRandom

/**
 * Settings → App → Back up & restore. Sidekick's settings, favourites, watch history and resume
 * points, saved to a file on your phone and put back from one (Android's own backup is off, so a
 * reinstall or a new TV otherwise starts from nothing).
 *
 * A small page on the home network does it, only while the QR code is on screen, with a new key
 * each time. Restoring asks on the TV before anything is replaced.
 */
@android.annotation.SuppressLint("StaticFieldLeak") // holds only the application context
object Backup {
    private const val FORMAT = 1
    /** A backup with a long history and many chat layouts is still well under this. */
    private const val MAX_BYTES = 512 * 1024

    /**
     * Never in a backup: the sign-in (it can't move between devices and would be a secret in a
     * file), this TV's own state (phone keys, home-screen row, update checks) and per-account lists.
     */
    private val EXCLUDED_PREFIXES = listOf(
        "session_token", "session_expired", "account_", "pending_follows", "phone_chat_key", "tv_channel_id",
        "update_", "whats_new_seen", "pairing", "crash",
    )

    private fun included(key: String) = EXCLUDED_PREFIXES.none { key.startsWith(it) }

    private var app: Context? = null
    private val server = LocalHttp.Server("backup") { handle(it) }
    @Volatile private var key = ""
    private val main = Handler(Looper.getMainLooper())
    private val hits = LocalHttp.RateLimit(max = 30)

    /** Called on the main thread when a phone sends a backup to restore: the TV asks before applying it. */
    @Volatile var onRestoreOffered: ((JSONObject) -> Unit)? = null

    /** Starts the page; returns its address for the QR code, or null without a network. */
    fun start(context: Context): String? {
        app = context.applicationContext
        key = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        if (!server.running && !server.start(listOf(8793, 8794, 0))) return null
        return LocalHttp.lanAddress()?.let { "http://$it:${server.port}/?k=$key" }
    }

    fun stop() {
        server.stop()
        key = ""
        onRestoreOffered = null
    }

    /** Everything worth keeping, as JSON. */
    fun export(context: Context): JSONObject {
        val sp = context.getSharedPreferences("sidekick", Context.MODE_PRIVATE)
        val values = JSONObject()
        for ((k, v) in sp.all) {
            if (!included(k) || v == null) continue
            val entry = when (v) {
                is Boolean -> JSONObject().put("t", "b").put("v", v)
                is Int -> JSONObject().put("t", "i").put("v", v)
                is Long -> JSONObject().put("t", "l").put("v", v)
                is Float -> JSONObject().put("t", "f").put("v", v.toDouble())
                is String -> JSONObject().put("t", "s").put("v", v)
                is Set<*> -> JSONObject().put("t", "set").put("v", JSONArray(v.map { it.toString() }))
                else -> continue
            }
            values.put(k, entry)
        }
        return JSONObject().put("app", "sidekick").put("format", FORMAT)
            .put("version", com.diriktv.sidekick.BuildConfig.VERSION_NAME).put("saved", System.currentTimeMillis())
            .put("prefs", values)
    }

    /** How many favourites and history entries [backup] holds, for the TV's question. */
    fun describe(backup: JSONObject): String {
        val p = backup.optJSONObject("prefs") ?: return "an empty backup"
        fun count(key: String) = runCatching { JSONArray(p.optJSONObject(key)?.optString("v") ?: "[]").length() }.getOrDefault(0)
        return "${count("favorites")} favourites, ${count("history")} watched and ${p.length()} settings in all"
    }

    /** True if [backup] is a Sidekick backup this version can read. */
    fun valid(backup: JSONObject) = backup.optString("app") == "sidekick" && backup.optInt("format") in 1..FORMAT &&
        backup.optJSONObject("prefs") != null

    /**
     * Replaces the settings, favourites and history with [backup]'s. The sign-in and this TV's
     * own state stay as they are.
     */
    // Written straight away (commit, not apply): the screen restarts right after and reads it back.
    @android.annotation.SuppressLint("ApplySharedPref")
    fun restore(context: Context, backup: JSONObject) {
        val sp = context.getSharedPreferences("sidekick", Context.MODE_PRIVATE)
        val prefs = backup.getJSONObject("prefs")
        val e = sp.edit()
        sp.all.keys.filter { included(it) }.forEach { e.remove(it) }
        for (k in prefs.keys()) {
            if (!included(k)) continue
            val o = prefs.optJSONObject(k) ?: continue
            when (o.optString("t")) {
                "b" -> e.putBoolean(k, o.optBoolean("v"))
                "i" -> e.putInt(k, o.optInt("v"))
                "l" -> e.putLong(k, o.optLong("v"))
                "f" -> e.putFloat(k, o.optDouble("v").toFloat())
                "s" -> e.putString(k, o.optString("v"))
                "set" -> o.optJSONArray("v")?.let { a -> e.putStringSet(k, (0 until a.length()).map { a.optString(it) }.toSet()) }
            }
        }
        e.commit()
        Prefs.forgetCached()
        Follows.invalidate()
        com.diriktv.sidekick.ui.Motion.reduced = Prefs(context).reduceMotion
    }

    private fun handle(s: Socket) {
        val req = LocalHttp.read(s, maxBody = MAX_BYTES) ?: return
        if (!LocalHttp.keyMatches(req.query["k"], key)) return LocalHttp.respond(s, 403, "text/plain", "This link has expired. Open Back up & restore on your TV again.")
        if (!hits.allow()) return LocalHttp.respond(s, 429, "text/plain", "Too many requests. Wait a moment.")
        when {
            req.method == "GET" && req.path == "/" -> LocalHttp.respond(s, 200, "text/html; charset=utf-8", PAGE)
            req.method == "GET" && req.path == "/backup.json" -> {
                val ctx = app ?: return LocalHttp.respond(s, 404, "text/plain", "Not found")
                LocalHttp.respondBytes(s, 200, "application/json", export(ctx).toString(1).toByteArray(Charsets.UTF_8),
                    extraHeaders = "Content-Disposition: attachment; filename=\"sidekick-backup.json\"\r\n")
            }
            req.method == "POST" && req.path == "/restore" -> {
                val backup = runCatching { JSONObject(req.body) }.getOrNull()
                val ok = backup != null && valid(backup)
                if (ok) main.post { onRestoreOffered?.invoke(backup!!) }
                LocalHttp.respond(s, 200, "application/json", JSONObject().put("ok", ok)
                    .put("error", if (ok) null else "That isn't a Sidekick backup file.").toString())
            }
            else -> LocalHttp.respond(s, 404, "text/plain", "Not found")
        }
    }

    private val PAGE = """
<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="referrer" content="no-referrer">
<title>Sidekick backup</title>
<style>
:root{--g:#53FC18;--bg:#0B0E0F;--s:#16191C;--t:#EFEFF1;--t2:#A9ADB4}
body{margin:0;background:var(--bg);color:var(--t);font:16px/1.5 system-ui,-apple-system,Segoe UI,Roboto,sans-serif;padding:24px 20px}
h1{font-size:22px;margin:0 0 6px}p{color:var(--t2);margin:0 0 18px}
.card{background:var(--s);border-radius:16px;padding:18px;margin-bottom:16px}
h2{font-size:17px;margin:0 0 4px}
a.b,label.b{display:block;text-align:center;height:48px;line-height:48px;border-radius:24px;background:var(--g);color:#000;font-weight:800;text-decoration:none;margin-top:12px;cursor:pointer}
input{display:none}#msg{margin-top:12px;color:var(--t2)}#msg.err{color:#ff8a8a}
</style></head><body>
<h1>Back up &amp; restore</h1>
<p>Your Sidekick settings, favourites, watch history and where you stopped in past broadcasts. Your Kick sign-in isn't included.</p>
<div class="card"><h2>Save a backup</h2><div style="color:var(--t2)">Keeps a file on this phone.</div>
<a class="b" id="dl" href="#">Download backup</a></div>
<div class="card"><h2>Restore</h2><div style="color:var(--t2)">Pick a backup file. Your TV asks before replacing anything.</div>
<label class="b" for="f">Choose a backup file</label><input type="file" id="f" accept=".json,application/json"><div id="msg"></div></div>
<script>
var k=new URLSearchParams(location.search).get('k');
document.getElementById('dl').href='/backup.json?k='+k;
function msg(t,err){var m=document.getElementById('msg');m.textContent=t;m.className=err?'err':''}
document.getElementById('f').onchange=function(e){var file=e.target.files[0];if(!file)return;
 if(file.size>500000)return msg('That file is too big to be a Sidekick backup.',true);
 file.text().then(function(text){return fetch('/restore?k='+k,{method:'POST',body:text})}).then(function(r){return r.json()})
  .then(function(r){msg(r.ok?'Sent. Confirm on your TV to restore it.':r.error,!r.ok)})
  .catch(function(){msg("Couldn't reach your TV. Is your phone on the same Wi-Fi?",true)})};
</script></body></html>
"""
}
