package com.nuvio.app.core.sync

import kotlinx.coroutines.flow.Flow
import platform.Foundation.NSNotificationCenter
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationState
import platform.UIKit.UIApplicationWillResignActiveNotification

internal actual object AppForegroundMonitor {
    // Observers first, then the current state: see appVisibilityEvents (a launch-time activation was being lost).
    actual fun events(): Flow<AppVisibility> = appVisibilityEvents(
        isActive = { UIApplication.sharedApplication.applicationState == UIApplicationState.UIApplicationStateActive },
        observe = { onActive, onResign ->
            val center = NSNotificationCenter.defaultCenter
            val active = center.addObserverForName(name = UIApplicationDidBecomeActiveNotification, `object` = null, queue = null) { _ -> onActive() }
            val resign = center.addObserverForName(name = UIApplicationWillResignActiveNotification, `object` = null, queue = null) { _ -> onResign() }
            val unregister: () -> Unit = {
                center.removeObserver(active)
                center.removeObserver(resign)
            }
            unregister
        },
    )
}
