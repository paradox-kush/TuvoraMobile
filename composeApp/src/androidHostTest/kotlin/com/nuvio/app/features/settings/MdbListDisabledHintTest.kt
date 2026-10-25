package com.nuvio.app.features.settings

import android.app.Application
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.mdblist.MdbListSettings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MdbListDisabledHintTest {
    @get:Rule val compose = createComposeRule()
    private val hint = "Ratings are off. Turn on Enable MDBList Ratings to show them."
    @Test fun configuredButDisabledRatingsExplainHowToEnableThem() {
        compose.setContent { NuvioTheme { LazyColumn {
            mdbListSettingsContent(false, MdbListSettings(apiKey = "synthetic-test-key"))
        } } }
        compose.onNodeWithText(hint).assertExists()
    }
    @Test fun enabledRatingsDoNotShowDisabledHint() {
        compose.setContent { NuvioTheme { LazyColumn {
            mdbListSettingsContent(false, MdbListSettings(apiKey = "synthetic-test-key", enabled = true))
        } } }
        compose.onNodeWithText(hint).assertDoesNotExist()
    }
}
