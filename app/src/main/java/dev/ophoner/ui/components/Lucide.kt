package dev.ophoner.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ophoner.R
import dev.ophoner.ui.theme.tokens

/**
 * Lucide icons (ISC licence), vendored as vector drawables with the stroke thinned
 * from 2.0 to 1.6 to match the AndBrowser icon weight.
 */
object Lucide {
    @DrawableRes val ArrowUp = R.drawable.lucide_arrow_up
    @DrawableRes val ArrowLeft = R.drawable.lucide_arrow_left
    @DrawableRes val ArrowUpRight = R.drawable.lucide_arrow_up_right
    @DrawableRes val PanelLeft = R.drawable.lucide_panel_left
    @DrawableRes val Settings = R.drawable.lucide_settings_2
    @DrawableRes val FolderPlus = R.drawable.lucide_folder_plus
    @DrawableRes val Folder = R.drawable.lucide_folder
    @DrawableRes val FolderOpen = R.drawable.lucide_folder_open
    @DrawableRes val Plus = R.drawable.lucide_plus
    @DrawableRes val X = R.drawable.lucide_x
    @DrawableRes val Trash = R.drawable.lucide_trash_2
    @DrawableRes val Pencil = R.drawable.lucide_pencil
    @DrawableRes val Copy = R.drawable.lucide_copy
    @DrawableRes val Square = R.drawable.lucide_square
    @DrawableRes val Terminal = R.drawable.lucide_terminal
    @DrawableRes val FileText = R.drawable.lucide_file_text
    @DrawableRes val FilePen = R.drawable.lucide_file_pen
    @DrawableRes val FileSymlink = R.drawable.lucide_file_symlink
    @DrawableRes val Search = R.drawable.lucide_search
    @DrawableRes val Globe = R.drawable.lucide_globe
    @DrawableRes val Smartphone = R.drawable.lucide_smartphone
    @DrawableRes val Wrench = R.drawable.lucide_wrench
    @DrawableRes val ChevronDown = R.drawable.lucide_chevron_down
    @DrawableRes val ChevronRight = R.drawable.lucide_chevron_right
    @DrawableRes val Check = R.drawable.lucide_check
    @DrawableRes val Contrast = R.drawable.lucide_contrast
    @DrawableRes val Palette = R.drawable.lucide_palette
    @DrawableRes val Type = R.drawable.lucide_type
    @DrawableRes val TriangleAlert = R.drawable.lucide_triangle_alert
    @DrawableRes val Download = R.drawable.lucide_download
    @DrawableRes val Upload = R.drawable.lucide_upload
    @DrawableRes val SquarePen = R.drawable.lucide_square_pen
    @DrawableRes val Rocket = R.drawable.lucide_rocket
    @DrawableRes val LayoutGrid = R.drawable.lucide_layout_grid
    @DrawableRes val Shield = R.drawable.lucide_shield
    @DrawableRes val MessageSquare = R.drawable.lucide_message_square
    @DrawableRes val Image = R.drawable.lucide_image
    @DrawableRes val Bell = R.drawable.lucide_bell
    @DrawableRes val Calendar = R.drawable.lucide_calendar
    @DrawableRes val Contact = R.drawable.lucide_contact
    @DrawableRes val Phone = R.drawable.lucide_phone
    @DrawableRes val MapPin = R.drawable.lucide_map_pin
    @DrawableRes val BookOpen = R.drawable.lucide_book_open
    @DrawableRes val ScanEye = R.drawable.lucide_scan_eye
    @DrawableRes val Pointer = R.drawable.lucide_mouse_pointer_click
    @DrawableRes val Sparkles = R.drawable.lucide_sparkles
}

@Composable
fun LucideIcon(
    @DrawableRes icon: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = tokens().icon,
    size: Dp = 20.dp,
) {
    Icon(
        painter = painterResource(icon),
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier.size(size),
    )
}
