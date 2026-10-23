package com.zarc.vault.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PhotoAlbum
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VerticalSplit
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The icon vocabulary of the gallery.
 *
 * The stock Material `filled` set is replaced by the softer `rounded` family,
 * and the one icon the family does not have, an import arrow dropping into a
 * tray, is drawn here so that the toolbar reads as one set instead of a mix.
 */
internal object ZarcIcons {

    /** An arrow dropping into a tray: import into the vault. */
    val Import: ImageVector = ImageVector.Builder(
        name = "ZarcImport",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(10.9f, 3f)
            lineTo(13.1f, 3f)
            lineTo(13.1f, 9.8f)
            lineTo(16f, 6.9f)
            lineTo(17.6f, 8.5f)
            lineTo(12f, 13.9f)
            lineTo(6.4f, 8.5f)
            lineTo(8f, 6.9f)
            lineTo(10.9f, 9.8f)
            close()
        }
        path(fill = SolidColor(Color.Black)) {
            moveTo(4f, 14.8f)
            lineTo(6.2f, 14.8f)
            lineTo(6.2f, 17.8f)
            lineTo(17.8f, 17.8f)
            lineTo(17.8f, 14.8f)
            lineTo(20f, 14.8f)
            lineTo(20f, 20f)
            lineTo(4f, 20f)
            close()
        }
    }.build()

    val Search = Icons.Rounded.Search
    val Close = Icons.Rounded.Close
    val More = Icons.Rounded.MoreHoriz
    val MoreVert = Icons.Rounded.MoreVert
    val Back = Icons.AutoMirrored.Rounded.ArrowBack
    val Check = Icons.Rounded.Check
    val Chevron = Icons.AutoMirrored.Rounded.KeyboardArrowRight

    val Add = Icons.Rounded.Add
    val Roll = Icons.Rounded.PhotoLibrary
    val Albums = Icons.Rounded.PhotoAlbum
    val Menu = Icons.Rounded.Menu

    val Lock = Icons.Rounded.Lock
    val Bin = Icons.Rounded.DeleteOutline
    val EmptyBin = Icons.Rounded.DeleteSweep
    val Settings = Icons.Rounded.Settings

    val Sort = Icons.Rounded.SwapVert
    val Filter = Icons.Rounded.Tune
    val SelectAll = Icons.Rounded.SelectAll
    val Move = Icons.AutoMirrored.Rounded.DriveFileMove
    val Restore = Icons.Rounded.Restore
    val Delete = Icons.Rounded.Delete
    val Edit = Icons.Rounded.Edit
    val DateRange = Icons.Rounded.DateRange
    val Star = Icons.Rounded.Star
    val StarBorder = Icons.Rounded.StarBorder

    val Split = Icons.Rounded.VerticalSplit
    val Swap = Icons.Rounded.SwapHoriz
    val OpenWith = Icons.AutoMirrored.Rounded.OpenInNew
    val Shield = Icons.Rounded.Shield
    val Info = Icons.Rounded.Info

    val Play = Icons.Rounded.PlayArrow
    val Pause = Icons.Rounded.Pause
    val Video = Icons.Rounded.Videocam
}