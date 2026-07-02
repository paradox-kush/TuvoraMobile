package com.nuvio.app.features.iptv

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.settings.SettingsGroup
import com.nuvio.app.features.settings.SettingsGroupDivider
import com.nuvio.app.features.settings.SettingsSection

/** Which account the "Content & Categories" settings sub-page edits — set right before navigating. */
internal object XtreamContentPage {
    var accountId: String? = null
        private set

    fun open(id: String) {
        accountId = id
    }
}

private val TYPE_LABELS = listOf(
    CONTENT_TYPE_LIVE to "Live TV",
    CONTENT_TYPE_MOVIES to "Movies",
    CONTENT_TYPE_SERIES to "Series",
)

/**
 * "Content & Categories" for one playlist: three content-type toggle rows (with selected-category
 * counts) that expand into a per-type category checklist with Select All / Deselect All.
 * Option-only edits — persisted via XtreamRepository.updateOptions (sync-pushed, no re-verify).
 */
internal fun LazyListScope.xtreamContentSettingsContent(
    isTablet: Boolean,
    state: XtreamUiState,
) {
    item {
        val account = state.accounts.firstOrNull { it.id == XtreamContentPage.accountId }
        if (account == null) {
            Text(
                text = "This playlist no longer exists.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            return@item
        }

        var expandedType by remember(account.id) { mutableStateOf<String?>(null) }
        // One cheap categories call per type (id + name only) so the counts can render.
        var categories by remember(account.id) { mutableStateOf<Map<String, List<XtreamCategory>>>(emptyMap()) }
        LaunchedEffect(account.id) {
            for ((type, _) in TYPE_LABELS) {
                if (categories[type] != null) continue
                val fetched = when (type) {
                    CONTENT_TYPE_LIVE -> XtreamClient.liveCategories(account)
                    CONTENT_TYPE_MOVIES -> XtreamClient.vodCategories(account)
                    else -> XtreamClient.seriesCategories(account)
                }.getOrNull()
                if (fetched != null) categories = categories + (type to fetched)
            }
        }

        val type = expandedType
        if (type == null) {
            ContentTypeList(
                account = account,
                categories = categories,
                isTablet = isTablet,
                onOpenType = { expandedType = it },
            )
        } else {
            CategoryChecklist(
                account = account,
                type = type,
                label = TYPE_LABELS.first { it.first == type }.second,
                categories = categories[type],
                onBack = { expandedType = null },
            )
        }
    }
}

@Composable
private fun ContentTypeList(
    account: XtreamAccount,
    categories: Map<String, List<XtreamCategory>>,
    isTablet: Boolean,
    onOpenType: (String) -> Unit,
) {
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
                ContentTypeRow(
                    title = label,
                    subtitle = subtitle,
                    checked = enabled,
                    isTablet = isTablet,
                    onToggle = { on ->
                        XtreamRepository.updateOptions(account.id) { acc ->
                            acc.copy(contentTypes = if (on) acc.contentTypes + type else acc.contentTypes - type)
                        }
                    },
                    onClick = { if (enabled) onOpenType(type) },
                )
            }
        }
        Text(
            text = "Tap a content type to choose its categories. Toggling a type off hides it from browse and search for this playlist.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun ContentTypeRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    isTablet: Boolean,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    val verticalPadding = if (isTablet) 16.dp else 14.dp
    val horizontalPadding = if (isTablet) 20.dp else 16.dp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onToggle)
    }
}

@Composable
private fun CategoryChecklist(
    account: XtreamAccount,
    type: String,
    label: String,
    categories: List<XtreamCategory>?,
    onBack: () -> Unit,
) {
    val selection = account.categorySelections.forType(type)
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Column {
                Text(
                    text = "$label categories",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                val count = when {
                    categories == null -> "Loading…"
                    selection == null -> "${categories.size}/${categories.size} selected"
                    else -> "${selection.size}/${categories.size} selected"
                }
                Text(
                    text = count,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            // Select all = null selection: every category, including ones the provider adds later.
            TextButton(onClick = { setSelection(account.id, type, null) }) { Text("Select all") }
            TextButton(onClick = { setSelection(account.id, type, emptyList()) }) { Text("Deselect all") }
        }
        when {
            categories == null -> Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(strokeWidth = 2.dp) }
            categories.isEmpty() -> Text(
                text = "No categories on this playlist.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
            else -> Column {
                val allIds = remember(categories) { categories.map { it.id } }
                categories.forEach { category ->
                    val checked = selection == null || category.id in selection
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { toggleCategory(account.id, type, allIds, category.id) }
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { toggleCategory(account.id, type, allIds, category.id) },
                        )
                        Text(
                            text = category.name.ifBlank { "Other" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
            }
        }
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
