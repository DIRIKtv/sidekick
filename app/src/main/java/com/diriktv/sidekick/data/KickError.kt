package com.diriktv.sidekick.data

import java.io.IOException

/** Why a call to Kick failed, so screens can say it in plain words instead of "HTTP 403 for /api/…". */
class KickException(val kind: Kind, val code: Int = 0, message: String = kind.name) : IOException(message) {
    enum class Kind {
        /** Cloudflare's bot check (or another 403) turned the request away. */
        BLOCKED,
        RATE_LIMITED,
        /** The account's session is no longer accepted (401 on a signed-in call). */
        SIGNED_OUT,
        NOT_FOUND,
        /** Kick's side failed (5xx). */
        SERVER,
        OTHER,
    }
}

object KickErrors {
    /** A short, friendly sentence for [e], in the same voice as the player's error card. */
    fun describe(e: Throwable?): String = when {
        e is KickException -> when (e.kind) {
            KickException.Kind.BLOCKED ->
                "Kick's security check turned Sidekick away for now. This usually clears up within a few minutes."
            KickException.Kind.RATE_LIMITED -> "Kick is limiting requests right now. Try again in a minute."
            KickException.Kind.SIGNED_OUT -> "Your Kick sign-in has expired. Sign in again under Settings → Account."
            KickException.Kind.NOT_FOUND -> "Kick couldn't find that. It may have been renamed or removed."
            KickException.Kind.SERVER -> "Kick is having trouble right now. Try again in a little while."
            KickException.Kind.OTHER -> "Kick didn't answer the way Sidekick expected. Try again in a little while."
        }
        isOffline(e) -> "Sidekick couldn't reach Kick. Check your internet connection, then try again."
        else -> "Something went wrong talking to Kick. Try again in a little while."
    }

    /** No connection at all (as opposed to Kick answering with an error). */
    fun isOffline(e: Throwable?): Boolean {
        var t = e
        while (t != null) {
            if (t is KickException) return false
            if (t is java.net.UnknownHostException || t is java.net.SocketException ||
                t is java.io.InterruptedIOException || t is java.io.EOFException ||
                t is java.net.NoRouteToHostException || t is javax.net.ssl.SSLException
            ) return true
            t = t.cause
        }
        return false
    }
}
