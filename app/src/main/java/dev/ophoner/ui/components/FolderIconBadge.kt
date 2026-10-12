package dev.ophoner.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun FolderIconBadge(
    tint: Color,
    expanded: Boolean = false,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    IconMark(
        icon = if (expanded) Lucide.FolderOpen else Lucide.Folder,
        modifier = modifier,
        size = size,
        tint = tint,
        background = tint.copy(alpha = 0.12f),
    )
}
