package com.diriktv.sidekick.data

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.SecureRandom
import java.util.concurrent.Executors

/**
 * Chat from your phone: a small page on the home network that shows the chat of whatever the TV
 * is playing and sends what you type to it, as your Kick account. The page follows the TV, so
 * one scan keeps working as you change streams. Its key is kept between runs, so a phone that
 * saved the page can come straight back.
 */
object PhoneChat {
    /** The live chat the TV is showing, set by the player. */
    data class Target(val slug: String, val username: String, val avatar: String?, val chatroomId: Int)

    @Volatile var target: Target? = null
        set(v) {
            if (v?.slug != field?.slug) synchronized(recent) { recent.clear() }
            field = v
        }

    private val recent = ArrayDeque<ChatMessage>()
    private var seq = 0L
    private val seqOf = HashMap<String, Long>()

    private var socket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    private var secret = ""
    private var port = 0

    /** Called for each message shown on the TV, so the phone sees the same chat. */
    fun record(m: ChatMessage) {
        if (socket == null || m.isSystem) return
        synchronized(recent) {
            recent.addLast(m)
            seqOf[m.id] = ++seq
            while (recent.size > 60) seqOf.remove(recent.removeFirst().id)
        }
    }

    /** Starts the server if needed and returns the address for the QR code, or null without a network. */
    fun start(context: Context): String? {
        val sp = context.getSharedPreferences("sidekick", Context.MODE_PRIVATE)
        if (socket == null) {
            secret = sp.getString("phone_chat_key", null) ?: ByteArray(12).also { SecureRandom().nextBytes(it) }
                .joinToString("") { "%02x".format(it) }.also { sp.edit().putString("phone_chat_key", it).apply() }
            for (p in listOf(8790, 8791, 8792, 0)) {
                socket = runCatching { ServerSocket(p) }.getOrNull() ?: continue
                port = socket!!.localPort
                break
            }
            val server = socket ?: return null
            pool.execute {
                Thread.currentThread().name = "phone-chat"
                while (!server.isClosed) {
                    val client = try { server.accept() } catch (e: SocketException) { break }
                    pool.execute { runCatching { handle(client) } }
                }
            }
        }
        return LocalHttp.lanAddress()?.let { "http://$it:$port/?k=$secret" }
    }

    private fun handle(client: Socket) = client.use { s ->
        val req = LocalHttp.read(s) ?: return
        if (req.query["k"] != secret) return LocalHttp.respond(s, 403, "text/plain", "This link has expired. Scan the QR code on your TV again.")
        when {
            req.method == "GET" && req.path == "/" -> LocalHttp.respond(s, 200, "text/html; charset=utf-8", PAGE)
            req.method == "GET" && req.path == "/state" -> LocalHttp.respond(s, 200, "application/json", state(req.query["after"]?.toLongOrNull() ?: 0).toString())
            req.method == "POST" && req.path == "/send" -> {
                val text = runCatching { JSONObject(req.body).optString("text") }.getOrNull().orEmpty().trim().take(500)
                val t = target
                val error = when {
                    text.isEmpty() -> "Type a message first."
                    t == null -> "Nothing is playing on your TV right now."
                    KickApi.authToken == null -> "Sign in to Kick on your TV first (Settings → Account)."
                    else -> runBlocking { KickApi.sendChat(t.chatroomId, text) }
                }
                LocalHttp.respond(s, 200, "application/json", JSONObject().put("ok", error == null).put("error", error).toString())
            }
            else -> LocalHttp.respond(s, 404, "text/plain", "Not found")
        }
    }

    private fun state(after: Long): JSONObject {
        val t = target
        val messages = JSONArray()
        var last = after
        var reset = false
        synchronized(recent) {
            // The app restarted and its count began again: the page should start over.
            if (after > seq) { reset = true; last = 0 }
            for (m in recent) {
                val n = seqOf[m.id] ?: continue
                if (n <= after && !reset) continue
                last = maxOf(last, n)
                messages.put(JSONObject().put("u", m.username).put("c", m.color).put("t", m.content))
            }
        }
        return JSONObject()
            .put("signedIn", KickApi.authToken != null)
            .put("channel", t?.let { JSONObject().put("name", it.username).put("avatar", it.avatar) })
            .put("messages", messages)
            .put("last", last)
            .put("reset", reset)
    }

    private val PAGE = """
<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<title>Sidekick chat</title>
<style>
:root{--g:#53FC18;--bg:#0B0E0F;--s:#16191C;--s2:#24272C;--t:#EFEFF1;--t2:#A9ADB4}
*{box-sizing:border-box}html,body{height:100%}
body{margin:0;background:var(--bg);color:var(--t);font:16px/1.4 system-ui,-apple-system,Segoe UI,Roboto,sans-serif;display:flex;flex-direction:column}
header{display:flex;align-items:center;gap:12px;padding:14px 16px;background:var(--s);border-bottom:1px solid #000}
header img{width:36px;height:36px;border-radius:50%;background:var(--s2);object-fit:cover}
.who{font-weight:700}.sub{font-size:13px;color:var(--t2)}
#log{flex:1;overflow-y:auto;padding:10px 16px}
.m{padding:3px 0;word-wrap:break-word}.m b{font-weight:700}.m img{height:1.6em;vertical-align:middle}
.note{margin:10px 16px;padding:10px 12px;border-radius:10px;background:var(--s2);color:var(--t2);font-size:14px}
.err{background:#3a1416;color:#ff8a8a}
form{display:flex;gap:8px;padding:10px 12px calc(10px + env(safe-area-inset-bottom));background:var(--s)}
input{flex:1;min-width:0;height:46px;padding:0 14px;border:1px solid #3A3E45;border-radius:23px;background:var(--bg);color:var(--t);font:inherit;outline:none}
input:focus{border-color:var(--g)}
button{height:46px;padding:0 20px;border:0;border-radius:23px;background:var(--g);color:#000;font:inherit;font-weight:800}
button:disabled{opacity:.5}.hide{display:none}
</style></head><body>
<header><img id="av" alt=""><div><div class="who" id="who">Sidekick</div><div class="sub" id="sub">Connecting to your TV…</div></div></header>
<div id="log"></div>
<div id="note" class="note hide"></div>
<form id="f"><input id="t" placeholder="Send a message" autocomplete="off" maxlength="500" enterkeyhint="send"><button id="b">Send</button></form>
<script>
var k=new URLSearchParams(location.search).get('k'),last=0,channel=null;
function g(i){return document.getElementById(i)}
function esc(s){return s.replace(/[&<>"]/g,function(c){return{'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]})}
function render(t){return esc(t).replace(/\[emote:(\d+):([^\]]*)\]/g,function(_,id,n){return '<img alt="'+n+'" src="https://files.kick.com/emotes/'+id+'/fullsize">'})}
function note(text,err){var n=g('note');n.textContent=text||'';n.classList.toggle('hide',!text);n.classList.toggle('err',!!err)}
function add(m){var log=g('log'),stick=log.scrollHeight-log.scrollTop-log.clientHeight<60,d=document.createElement('div');d.className='m';
 d.innerHTML='<b style="color:'+esc(m.c||'#53FC18')+'">'+esc(m.u)+'</b>: '+render(m.t);log.appendChild(d);
 while(log.children.length>150)log.removeChild(log.firstChild);if(stick)log.scrollTop=log.scrollHeight}
function poll(){fetch('/state?k='+k+'&after='+last).then(function(r){return r.json()}).then(function(s){
 var name=s.channel&&s.channel.name;
 if(name!==channel){channel=name;g('log').innerHTML='';}
 if(s.reset)g('log').innerHTML='';
 g('who').textContent=name||'Sidekick';g('av').src=(s.channel&&s.channel.avatar)||'';
 g('sub').textContent=name?'Chatting on your TV':'Nothing playing on your TV';
 s.messages.forEach(add);last=s.last;
 g('b').disabled=g('t').disabled=!name||!s.signedIn;
 if(!s.signedIn)note('Sign in to Kick on your TV to chat (Settings → Account). You can still read along.');
 else if(!name)note('Start a live stream on your TV. This page follows whatever it plays.');
 else if(!g('note').classList.contains('err'))note('');
 setTimeout(poll,1200)}).catch(function(){g('sub').textContent="Can't reach your TV. Is your phone on the same Wi-Fi?";setTimeout(poll,3000)})}
g('f').onsubmit=function(e){e.preventDefault();var t=g('t').value.trim();if(!t)return;g('b').disabled=true;
 fetch('/send?k='+k,{method:'POST',body:JSON.stringify({text:t})}).then(function(r){return r.json()}).then(function(r){
  g('b').disabled=false;if(r.ok){g('t').value='';note('')}else note(r.error,true)}).catch(function(){g('b').disabled=false;note("Couldn't reach your TV.",true)})};
poll();
</script></body></html>
"""
}
