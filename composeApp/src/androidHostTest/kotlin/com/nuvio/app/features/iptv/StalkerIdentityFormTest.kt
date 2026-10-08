package com.nuvio.app.features.iptv

import android.app.Application
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import com.nuvio.app.core.ui.NuvioTheme
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StalkerIdentityFormTest {
    @get:Rule val compose = createComposeRule()

    @AfterTest fun reset() {
        XtreamAddPage.openAdd()
        XtreamRepository.verifyForTest = null
        XtreamRepository.persistWriteForTest = null
        XtreamRepository.clearLocalState()
    }

    @Volatile private var saved = false

    private fun showForm() {
        val account = XtreamAccount(id = "stalker|http://portal.example.test|00:1A:79:AB:CD:EF",
            name = "Portal", baseUrl = "http://portal.example.test", username = "", password = "",
            sourceType = SOURCE_TYPE_STALKER, macAddress = "00:1A:79:AB:CD:EF", sendDeviceId = false)
        XtreamRepository.installAccountsForTest(listOf(account))
        XtreamRepository.persistWriteForTest = { _, _ -> }
        XtreamRepository.verifyForTest = { Result.success(Unit) }
        XtreamAddPage.openEdit(account.id)
        compose.setContent {
            val state by XtreamRepository.uiState.collectAsState()
            NuvioTheme { LazyColumn { xtreamAddPlaylistContent(false, state, { saved = true }) } }
        }
    }

    @Test fun savingWithoutChangingToggleKeepsSignatureDisabled() {
        showForm()
        compose.onNodeWithText("Send device signature").performScrollTo()
        compose.onNode(isToggleable()).assertIsOff()
        compose.onNodeWithText("Save changes").performScrollTo().performClick()
        compose.waitUntil(10_000) { saved }
        compose.runOnIdle { assertFalse(XtreamRepository.uiState.value.accounts.single().sendDeviceId) }
    }

    @Test fun freshToggleIsSaved() {
        showForm()
        compose.onNodeWithText("Send device signature").performScrollTo().performClick()
        compose.onNode(isToggleable()).assertIsOn()
        compose.onNodeWithText("Save changes").performScrollTo().performClick()
        compose.waitUntil(10_000) { XtreamRepository.uiState.value.accounts.single().sendDeviceId }
        compose.runOnIdle { assertTrue(XtreamRepository.uiState.value.accounts.single().sendDeviceId) }
    }
}
