package com.lumisignal.idxscreener

import android.app.Application
import com.lumisignal.idxscreener.data.AppRepository

class LumiApplication : Application() {
    val repository by lazy { AppRepository(this) }
}
