package com.nuvio.app.features.livetv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioTokens
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.recordings_rec_badge
import org.jetbrains.compose.resources.stringResource

/** "REC" on a guide programme that is scheduled or recording; styled like the CATCH-UP badge. [compact] = dot only, for narrow cells. */
@Composable
internal fun RecBadge(danger: Color, compact: Boolean = false) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(NuvioTokens.Radius.xs))
            .background(danger)
            .padding(horizontal = if (compact) NuvioTokens.Space.s4 else NuvioTokens.Space.s6, vertical = NuvioTokens.Space.s2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4),
    ) {
        Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(Color.White))
        if (!compact) {
            Text(
                text = stringResource(Res.string.recordings_rec_badge),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
