package com.nuvio.app.core.ui

import android.os.Process
import com.nuvio.app.AppExitReporter
import com.nuvio.app.SelfExitMarker
import kotlin.system.exitProcess

actual fun platformExitApp() {
    // Deliberate kill (frees RAM). Record why, durably, so the next launch reports a tagged self_exit instead of a
    // fatal-looking foreground SIGKILL.
    AppExitReporter.markSelfExit(null, SelfExitMarker.USER_EXIT)
    Process.killProcess(Process.myPid())
    exitProcess(0)
}
