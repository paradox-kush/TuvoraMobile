package com.nuvio.app.features.sportsdetail.internal

import platform.UIKit.UIAccessibilityIsReduceMotionEnabled

internal actual fun platformReduceMotion(): Boolean = UIAccessibilityIsReduceMotionEnabled()
