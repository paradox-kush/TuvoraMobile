package com.nuvio.app.features.iptv

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioActionLabel
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.settings.SettingsGroup
import com.nuvio.app.features.settings.SettingsGroupDivider
import com.nuvio.app.features.settings.SettingsSection
import com.nuvio.app.features.settings.SettingsSwitchRow

/**
 * Which playlist (and, on the checklist sub-page, which content type) the "Content & Categories"
 * settings pages edit — set right before navigating. `type` is null on the type-list page and holds
 * the drilled-into content type on the category-checklist page.
 */
internal object XtreamContentPage {
    var accountId: String? = null
        private set
    var type: String? = null
        private set

    fun open(id: String) {
        accountId = id
        type = null
    }

    /** Drill from the type list into one type's category checklist. */
    fun openChecklist(contentType: String) {
        type = contentType
    }
}

private val TYPE_LABELS = listOf(
    CONTENT_TYPE_LIVE to "Live TV",
    CONTENT_TYPE_MOVIES to "Movies",
    CONTENT_TYPE_SERIES to "Series",
)

private fun labelForType(type: String): String =
    TYPE_LABELS.firstOrNull { it.first == type }?.second ?: type

/** Carded fallback shown if the target playlist vanished (e.g. deleted on another device). */
@Composable
private fun PlaylistGoneCard(isTablet: Boolean) {
    val tokens = MaterialTheme.nuvio
    SettingsSection(title = "Content & Categories", isTablet = isTablet) {
        SettingsGroup(isTablet = isTablet) {
            Text(
                text = "This playlist no longer exists.",
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textPrimary,
                modifier = Modifier.padding(
                    horizontal = if (isTablet) NuvioTokens.Space.s20 else NuvioTokens.Space.s16,
                    vertical = if (isTablet) NuvioTokens.Space.s16 else NuvioTokens.Space.s14,
                ),
            )
        }
    }
}

/**
 * "Content & Categories" for one playlist: three content-type toggle rows (with selected-category
 * counts). Tapping an enabled row drills into that type's category checklist (a separate settings
 * page). Option-only edits — persisted via XtreamRepository.updateOptions (sync-pushed, no re-verify).
 */
internal fun LazyListScope.xtreamContentSettingsContent(
    isTablet: Boolean,
    state: XtreamUiState,
    onOpenType: (String) -> Unit,
) {
    item {
        val account = state.accounts.firstOrNull { it.id == XtreamContentPage.accountId }
        if (account == null) {
            PlaylistGoneCard(isTablet = isTablet)
            return@item
        }

        // One cheap categories call per type (id + name only) so the counts can render.
        var categories by remember(account.id) { mutableStateOf<Map<String, List<XtreamCategory>>>(emptyMap()) }
        var fetchAttempt by remember(account.id) { mutableStateOf(0) }
        LaunchedEffect(account.id, fetchAttempt) {
            val client = IptvClient.forAccount(account)   // xtream -> panel, m3u_url -> content DB
            for ((type, _) in TYPE_LABELS) {
                if (categories[type] != null) continue
                val fetched = when (type) {
                    CONTENT_TYPE_LIVE -> client.liveCategories(account)
                    CONTENT_TYPE_MOVIES -> client.vodCategories(account)
                    else -> client.seriesCategories(account)
                }.getOrNull()
                if (fetched != null) categories = categories + (type to fetched)
            }
        }

        ContentTypeList(
            account = account,
            categories = categories,
            isTablet = isTablet,
            onOpenType = { type ->
                if (categories[type] == null) fetchAttempt++   // re-attempt a failed/stuck fetch
                onOpenType(type)
            },
        )
    }
}

@Composable
private fun ContentTypeList(
    account: XtreamAccount,
    categories: Map<String, List<XtreamCategory>>,
    isTablet: Boolean,
    onOpenType: (String) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    SettingsSection(title = account.name, isTablet = isTablet) {
        SettingsGroup(isTablet = isTablet) {
            TYPE_LABELS.forEachIndexed { index, (type, label) ->
                if (index > 0) SettingsGroupDivider(isTablet = isTablet)
                val enabled = account.typeEnabled(type)
                val selection = account.categorySelections.forType(type)
                val total = categories[type]?.size
                val subtitle = when {
                    !enabled -> "Hidden"
                    selection == null -> "All categories"
                    total != null -> "${selection.size}/$total categories"
                    else -> "${selection.size} selected"
                }
                SettingsSwitchRow(
                    title = label,
                    description = subtitle,
                    checked = enabled,
                    isTablet = isTablet,
                    onRowClick = { if (enabled) onOpenType(type) },
                    onCheckedChange = { on ->
                        XtreamRepository.updateOptions(account.id) { acc ->
                            acc.copy(contentTypes = if (on) acc.contentTypes + type else acc.contentTypes - type)
                        }
                    },
                )
            }
        }
        Text(
            text = "Tap a content type to choose its categories. Toggling a type off hides it from browse and search for this playlist.",
            style = MaterialTheme.typography.bodySmall,
            color = tokens.colors.textMuted,
            modifier = Modifier.padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s10),
        )
    }
}

/**
 * Category checklist for one content type of one playlist — its own settings page so the standard
 * header + system back pop correctly to the type list. Fetches its own type's categories (id + name)
 * and offers Select All / Deselect All as section actions.
 */
internal fun LazyListScope.xtreamCategoryChecklistContent(
    isTablet: Boolean,
    state: XtreamUiState,
) {
    item {
        val account = state.accounts.firstOrNull { it.id == XtreamContentPage.accountId }
        val type = XtreamContentPage.type
        if (account == null || type == null) {
            PlaylistGoneCard(isTablet = isTablet)
            return@item
        }

        val label = labelForType(type)
        var categories by remember(account.id, type) { mutableStateOf<List<XtreamCategory>?>(null) }
        var failed by remember(account.id, type) { mutableStateOf(false) }
        var fetchAttempt by remember(account.id, type) { mutableStateOf(0) }
        LaunchedEffect(account.id, type, fetchAttempt) {
            failed = false   // back to the spinner while (re)fetching
            val client = IptvClient.forAccount(account)
            val fetched = when (type) {
                CONTENT_TYPE_LIVE -> client.liveCategories(account)
                CONTENT_TYPE_MOVIES -> client.vodCategories(account)
                else -> client.seriesCategories(account)
            }.getOrNull()
            if (fetched != null) categories = fetched else failed = true
        }

        CategoryChecklist(
            account = account,
            type = type,
            label = label,
            categories = categories,
            failed = failed,
            isTablet = isTablet,
            onRetry = { fetchAttempt++ },
        )
    }
}

@Composable
private fun CategoryChecklist(
    account: XtreamAccount,
    type: String,
    label: String,
    categories: List<XtreamCategory>?,
    failed: Boolean,
    isTablet: Boolean,
    onRetry: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val selection = account.categorySelections.forType(type)
    val count = when {
        categories == null -> if (failed) "Not loaded" else "Loading…"
        selection == null -> "${categories.size}/${categories.size} selected"
        else -> "${selection.size}/${categories.size} selected"
    }
    SettingsSection(
        title = "$label categories",
        isTablet = isTablet,
        actions = {
            // Select all = null selection: every category, including ones the provider adds later.
            NuvioActionLabel(
                text = "Select all",
                onClick = { setSelection(account.id, type, null) },
            )
            Spacer(modifier = Modifier.width(tokens.spacing.controlGap))
            NuvioActionLabel(
                text = "Deselect all",
                onClick = { setSelection(account.id, type, emptyList()) },
            )
        },
    ) {
        Text(
            text = count,
            style = MaterialTheme.typography.bodySmall,
            color = tokens.colors.textMuted,
            modifier = Modifier.padding(bottom = NuvioTokens.Space.s10),
        )
        SettingsGroup(isTablet = isTablet) {
            when {
                categories == null && failed -> Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = if (isTablet) NuvioTokens.Space.s20 else NuvioTokens.Space.s16,
                            vertical = if (isTablet) NuvioTokens.Space.s16 else NuvioTokens.Space.s14,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Couldn't load categories.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.colors.danger,
                        modifier = Modifier.weight(1f),
                    )
                    NuvioActionLabel(text = "Retry", onClick = onRetry)
                }
                categories == null -> Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = NuvioTokens.Space.s24),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(strokeWidth = tokens.borders.medium, color = tokens.colors.accent) }
                categories.isEmpty() -> Text(
                    text = "No categories on this playlist.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textMuted,
                    modifier = Modifier.padding(
                        horizontal = if (isTablet) NuvioTokens.Space.s20 else NuvioTokens.Space.s16,
                        vertical = if (isTablet) NuvioTokens.Space.s16 else NuvioTokens.Space.s14,
                    ),
                )
                else -> {
                    val allIds = remember(categories) { categories.map { it.id } }
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = if (isTablet) 900.dp else 680.dp),
                    ) {
                        itemsIndexed(categories, key = { _, category -> category.id }) { index, category ->
                            val checked = selection == null || category.id in selection
                            Column {
                                if (index > 0) SettingsGroupDivider(isTablet = isTablet)
                                CategoryChecklistRow(
                                    name = category.name.ifBlank { "Other" },
                                    checked = checked,
                                    isTablet = isTablet,
                                    onToggle = { toggleCategory(account.id, type, allIds, category.id) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryChecklistRow(
    name: String,
    checked: Boolean,
    isTablet: Boolean,
    onToggle: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val verticalPadding = if (isTablet) NuvioTokens.Space.s10 else NuvioTokens.Space.s8
    val horizontalPadding = if (isTablet) NuvioTokens.Space.s20 else NuvioTokens.Space.s16
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = { onToggle() },
            colors = CheckboxDefaults.colors(
                checkedColor = tokens.colors.accent,
                checkmarkColor = tokens.colors.onAccent,
                uncheckedColor = tokens.colors.borderDefault,
            ),
        )
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.textPrimary,
            modifier = Modifier
                .padding(start = NuvioTokens.Space.s4)
                .then(if (isTablet) Modifier.widthIn(max = 560.dp) else Modifier),
        )
    }
}

private fun setSelection(accountId: String, type: String, selection: List<String>?) {
    XtreamRepository.updateOptions(accountId) {
        it.copy(categorySelections = it.categorySelections.withType(type, selection))
    }
}

/** Toggling from "all" (null) materializes the full id list first, then flips the one id. */
private fun toggleCategory(accountId: String, type: String, allIds: List<String>, categoryId: String) {
    XtreamRepository.updateOptions(accountId) { acc ->
        val current = acc.categorySelections.forType(type) ?: allIds
        val updated = if (categoryId in current) current - categoryId else current + categoryId
        acc.copy(categorySelections = acc.categorySelections.withType(type, updated))
    }
}
