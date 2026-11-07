package com.nuvio.app.features.sportsdetail.api

import android.content.Context
import com.nuvio.app.features.sportsdetail.internal.SportsDetailStorage

object SportsDetailAndroid {
    fun initialize(context: Context) = SportsDetailStorage.initialize(context)
}
