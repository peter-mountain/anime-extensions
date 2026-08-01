package keiyoushi.utils

import android.util.Log

object ShindenLog {
    var enabled = false

    fun d(tag: String, msg: String) {
        if (enabled) Log.d(tag, msg)
    }

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        if (enabled) Log.e(tag, msg, tr)
    }

    fun w(tag: String, msg: String) {
        if (enabled) Log.w(tag, msg)
    }
}
