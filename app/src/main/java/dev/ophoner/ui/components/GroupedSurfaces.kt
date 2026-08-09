package dev.ophoner.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Flat grouped list container — hairline border, tight radius. */
val GroupedCorner = RoundedCornerShape(10.dp)

@Composable
fun GroupedSection(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(GroupedCorner)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(
                width = 0.5.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                shape = GroupedCorner,
            )
            .padding(vertical = 2.dp),
        content = content,
    )
}

@Composable
fun folderGlyphBackground(tint: Color): Color = tint.copy(alpha = 0.14f)
