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
@android.annotation.SuppressLint("StaticFieldLeak") // holds only the application context
object PhoneChat {
    /** The live chat the TV is showing, set by the player. */
    data class Target(
        val slug: String,
        val username: String,
        val avatar: String?,
        val chatroomId: Int,
        /** The channel's subscriber badges: months required -> image URL. */
        val subscriberBadges: List<Pair<Int, String>> = emptyList(),
    )

    /** The channel's 7TV emotes (name -> image URL), shown on the phone like on the TV. */
    @Volatile var sevenTv: Map<String, String> = emptyMap()

    /** Role badges Kick sends by type, and the artwork the app ships for them. */
    private val roleBadges = mapOf(
        "broadcaster" to com.diriktv.sidekick.R.drawable.badge_broadcaster,
        "staff" to com.diriktv.sidekick.R.drawable.badge_staff,
        "moderator" to com.diriktv.sidekick.R.drawable.badge_moderator,
        "verified" to com.diriktv.sidekick.R.drawable.badge_verified,
        "vip" to com.diriktv.sidekick.R.drawable.badge_vip,
        "og" to com.diriktv.sidekick.R.drawable.badge_og,
        "founder" to com.diriktv.sidekick.R.drawable.badge_founder,
        "sidekick" to com.diriktv.sidekick.R.drawable.badge_sidekick,
        "bot" to com.diriktv.sidekick.R.drawable.badge_bot,
        "subscriber" to com.diriktv.sidekick.R.drawable.badge_subscriber,
        "sub_gifter" to com.diriktv.sidekick.R.drawable.badge_sub_gifter,
    )
    private var app: Context? = null
    private val emoteCache = java.util.concurrent.ConcurrentHashMap<String, JSONObject>()

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
        app = context.applicationContext
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
            req.method == "GET" && req.path == "/badge" -> {
                val res = roleBadges[req.query["t"]] ?: return LocalHttp.respond(s, 404, "text/plain", "Not found")
                val bytes = app?.resources?.openRawResource(res)?.use { it.readBytes() } ?: return LocalHttp.respond(s, 404, "text/plain", "Not found")
                LocalHttp.respondBytes(s, 200, "image/png", bytes, cache = true)
            }
            req.method == "GET" && req.path == "/emotes" -> LocalHttp.respond(s, 200, "application/json", emotes().toString())
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
                messages.put(JSONObject().put("u", m.username).put("c", m.color).put("t", m.content).put("b", badges(m, t)))
            }
        }
        return JSONObject()
            .put("signedIn", KickApi.authToken != null)
            .put("channel", t?.let { JSONObject().put("name", it.username).put("avatar", it.avatar) })
            .put("messages", messages)
            .put("last", last)
            .put("reset", reset)
    }

    /** Badge images for a message, in the order the TV shows them: roles first, then the sender's picks. */
    private fun badges(m: ChatMessage, t: Target?): JSONArray {
        val out = JSONArray()
        val order = roleBadges.keys.toList()
        for (b in m.badges.sortedBy { order.indexOf(it.type).let { i -> if (i < 0) 99 else i } }) {
            if (b.type == "subscriber") {
                val subs = t?.subscriberBadges.orEmpty()
                val url = subs.lastOrNull { it.first <= b.count.coerceAtLeast(1) }?.second ?: subs.firstOrNull()?.second
                out.put(url ?: "/badge?t=subscriber&k=$secret")
            } else if (b.type in roleBadges) {
                out.put("/badge?t=${b.type}&k=$secret")
            }
        }
        m.badgeImages.forEach { out.put(it) }
        return out
    }

    /** The channel's Kick emotes for the quick bar, and its 7TV emotes for drawing messages. */
    private fun emotes(): JSONObject {
        val t = target ?: return JSONObject().put("kick", JSONArray()).put("seventv", JSONObject())
        val kick = emoteCache.getOrPut(t.slug) {
            val list = runCatching { runBlocking { KickApi.emotes(t.slug) } }.getOrDefault(emptyList())
            JSONObject().put("list", JSONArray().apply {
                list.forEach { put(JSONObject().put("id", it.id).put("n", it.name).put("s", it.subsOnly).put("set", it.set)) }
            })
        }
        return JSONObject().put("kick", kick.getJSONArray("list")).put("seventv", JSONObject(sevenTv as Map<*, *>))
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
.m .bd{height:1.15em;margin-right:3px;vertical-align:-.15em}
#qe{display:flex;gap:6px;overflow-x:auto;padding:8px 12px 0;background:var(--s);scrollbar-width:none}
#qe::-webkit-scrollbar{display:none}
#qe button{flex:none;width:42px;height:42px;padding:5px;border:0;border-radius:10px;background:var(--s2);position:relative}
#qe button img{width:100%;height:100%;object-fit:contain}
#qe button.lock::after{content:'';position:absolute;right:3px;bottom:3px;width:8px;height:8px;border-radius:50%;background:var(--g);box-shadow:0 0 0 2px var(--s2)}
#qe .tab{width:auto;padding:0 12px;color:var(--t2);font:600 13px system-ui;background:transparent}#qe .tab.on{color:#000;background:var(--g)}
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
<div id="qe" class="hide"></div>
<form id="f"><input id="t" placeholder="Send a message" autocomplete="off" maxlength="500" enterkeyhint="send"><button id="b">Send</button></form>
<script>
var k=new URLSearchParams(location.search).get('k'),last=0,channel=null,stv={},kick=[],tab='channel',picked={};
function g(i){return document.getElementById(i)}
function esc(s){return s.replace(/[&<>"]/g,function(c){return{'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]})}
// Kick emotes arrive as [emote:id:name]; 7TV emotes are plain words that match the channel's set.
function render(t){return t.split(/(\[emote:\d+:[^\]]*\])/).map(function(part){
 var m=part.match(/^\[emote:(\d+):([^\]]*)\]$/);
 if(m)return '<img alt="'+esc(m[2])+'" src="https://files.kick.com/emotes/'+m[1]+'/fullsize">';
 return part.split(/(\s+)/).map(function(w){return stv[w]?'<img alt="'+esc(w)+'" src="'+esc(stv[w])+'">':esc(w)}).join('')}).join('')}
function badges(b){return (b||[]).map(function(u){return '<img class="bd" alt="" src="'+esc(u)+'">'}).join('')}
function loadEmotes(){fetch('/emotes?k='+k).then(function(r){return r.json()}).then(function(e){stv=e.seventv||{};kick=e.kick||[];bar()}).catch(function(){})}
// The quick bar: the channel's emotes, Kick's global ones and 7TV. Tapping one puts :name: in the
// box; it's sent as the emote.
function bar(){var q=g('qe');q.innerHTML='';var sets=[['channel','Channel'],['global','Kick'],['7tv','7TV']];
 sets.forEach(function(s){if(s[0]==='7tv'&&!Object.keys(stv).length)return;if(s[0]!=='7tv'&&!kick.some(function(e){return e.set===s[0]}))return;
  var b=document.createElement('button');b.type='button';b.className='tab'+(tab===s[0]?' on':'');b.textContent=s[1];b.onclick=function(){tab=s[0];bar()};q.appendChild(b)});
 var list=tab==='7tv'?Object.keys(stv).slice(0,120).map(function(n){return{n:n,u:stv[n],stv:1}}):
  kick.filter(function(e){return e.set===tab}).map(function(e){return{n:e.n,u:'https://files.kick.com/emotes/'+e.id+'/fullsize',id:e.id,s:e.s}});
 list.forEach(function(e){var b=document.createElement('button');b.type='button';b.title=e.n+(e.s?' (subscribers)':'');if(e.s)b.className='lock';
  b.innerHTML='<img alt="'+esc(e.n)+'" src="'+esc(e.u)+'">';b.onclick=function(){if(!e.stv)picked[e.n]='[emote:'+e.id+':'+e.n+']';
   var t=g('t'),word=e.stv?e.n:':'+e.n+':';t.value=(t.value&&!/\s$/.test(t.value)?t.value+' ':t.value)+word+' ';t.focus()};q.appendChild(b)});
 q.classList.toggle('hide',!q.children.length)}
function encode(t){return t.replace(/:([A-Za-z0-9_]+):/g,function(all,n){return picked[n]||all})}
function note(text,err){var n=g('note');n.textContent=text||'';n.classList.toggle('hide',!text);n.classList.toggle('err',!!err)}
function add(m){var log=g('log'),stick=log.scrollHeight-log.scrollTop-log.clientHeight<60,d=document.createElement('div');d.className='m';
 d.innerHTML=badges(m.b)+'<b style="color:'+esc(m.c||'#53FC18')+'">'+esc(m.u)+'</b>: '+render(m.t);log.appendChild(d);
 while(log.children.length>150)log.removeChild(log.firstChild);if(stick)log.scrollTop=log.scrollHeight}
function poll(){fetch('/state?k='+k+'&after='+last).then(function(r){return r.json()}).then(function(s){
 var name=s.channel&&s.channel.name;
 if(name!==channel){channel=name;g('log').innerHTML='';picked={};tab='channel';loadEmotes();}
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
 fetch('/send?k='+k,{method:'POST',body:JSON.stringify({text:encode(t)})}).then(function(r){return r.json()}).then(function(r){
  g('b').disabled=false;if(r.ok){g('t').value='';note('')}else note(r.error,true)}).catch(function(){g('b').disabled=false;note("Couldn't reach your TV.",true)})};
poll();
</script></body></html>
"""
}
