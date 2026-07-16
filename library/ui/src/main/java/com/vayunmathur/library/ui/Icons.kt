package com.vayunmathur.library.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Attachment
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material.icons.filled.Work
import androidx.compose.material.icons.outlined.HighlightAlt
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavKey

/**
 * App-facing icon set. Every shared icon is rendered from `material-icons-extended`
 * (the single source of truth) and exposed only through these `IconXyz()` composables,
 * so apps never reference `androidx.compose.material.icons.*` directly.
 */
@Composable
fun AppIcon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    Icon(imageVector, contentDescription, modifier = modifier, tint = tint)
}

@Composable
fun IconAdd(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Add, "Add", modifier, tint)

@Composable
fun IconSave(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Save, "Save", modifier, tint)

@Composable
fun IconEdit(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Edit, "Edit", modifier, tint)

@Composable
fun IconDelete(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Delete, "Delete", modifier, tint)

@Composable
fun IconVerify(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.VerifiedUser, "Verify security code", modifier, tint)

@Composable
fun IconShare(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Share, "Share", modifier, tint)

@Composable
fun IconClose(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Close, "Close", modifier, tint)

@Composable
fun IconSettings(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Settings, "Settings", modifier, tint)

@Composable
fun IconVisible(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Visibility, "Visible", modifier, tint)

@Composable
fun IconSearch(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Search, "Search", modifier, tint)

@Composable
fun IconCopy(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.ContentCopy, "Copy", modifier, tint)

@Composable
fun IconCrop(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Crop, "Crop", modifier, tint)

@Composable
fun IconRotateLeft(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.RotateLeft, "Rotate Left", modifier, tint)

@Composable
fun IconRotateRight(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.RotateRight, "Rotate Right", modifier, tint)

@Composable
fun IconNavigation(navBack: () -> Unit) {
    IconButton({ navBack() }) {
        AppIcon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
    }
}

@Composable
fun IconNavigation(backStack: NavBackStack<out NavKey>, modifier: Modifier = Modifier) {
    IconButton({ backStack.pop() }, modifier = modifier) {
        AppIcon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
    }
}

@Composable
fun IconCheck(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Check, "Check", modifier, tint)

@Composable
fun IconStar(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Star, "Star", modifier, tint)

@Composable
fun IconPlay(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.PlayArrow, "Play", modifier, tint)

@Composable
fun IconPause(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Pause, "Pause", modifier, tint)

@Composable
fun IconStop(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Stop, "Stop", modifier, tint)

@Composable
fun IconMenu(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Menu, "Menu", modifier, tint)

@Composable
fun IconUpload(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Upload, "Upload", modifier, tint)

@Composable
fun IconUnarchive(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Unarchive, "Unarchive", modifier, tint)

@Composable
fun IconArchive(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Archive, "Archive", modifier, tint)

@Composable
fun IconChevronRight(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.ChevronRight, "Chevron", modifier, tint)

@Composable
fun IconUndo(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.AutoMirrored.Filled.Undo, "Undo", modifier, tint)

@Composable
fun IconForward(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.AutoMirrored.Filled.Forward, "Forward", modifier, tint)

@Composable
fun IconDraw(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Draw, "Draw", modifier, tint)

@Composable
fun IconBrush(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Brush, "Brush", modifier, tint)

@Composable
fun IconEraser(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.AutoMirrored.Filled.Backspace, "Eraser", modifier, tint)

@Composable
fun IconCamera(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.CameraAlt, "Camera", modifier, tint)

@Composable
fun IconBackup(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Backup, "Backup", modifier, tint)

@Composable
fun IconRestore(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.SettingsBackupRestore, "Restore", modifier, tint)

@Composable
fun IconMarkRead(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Check, "Mark Read", modifier, tint)

@Composable
fun IconMarkUnread(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Outlined.MailOutline, "Mark Unread", modifier, tint)

@Composable
fun IconFavorite(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Favorite, "Favorite", modifier, tint)

@Composable
fun IconFire(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Whatshot, "Fire", modifier, tint)

@Composable
fun IconInbox(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Outlined.Inbox, "Inbox", modifier, tint)

@Composable
fun IconSend(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.AutoMirrored.Filled.Send, "Send", modifier, tint)

@Composable
fun IconAttachment(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Attachment, "Attachment", modifier, tint)

@Composable
fun IconMail(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Outlined.MailOutline, "Mail", modifier, tint)

@Composable
fun IconDownload(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Download, "Download", modifier, tint)

@Composable
fun IconNavigationArrow(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Navigation, "Navigation arrow", modifier, tint)

@Composable
fun IconBack(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.AutoMirrored.Filled.ArrowBack, "Back", modifier, tint)

@Composable
fun IconRefresh(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Refresh, "Refresh", modifier, tint)

@Composable
fun IconHome(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Home, "Home", modifier, tint)

@Composable
fun IconWork(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Work, "Work", modifier, tint)

@Composable
fun IconImage(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Image, "Image", modifier, tint)

@Composable
fun IconKeyboardArrowUp(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.KeyboardArrowUp, "Up", modifier, tint)

@Composable
fun IconKeyboardArrowDown(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.KeyboardArrowDown, "Down", modifier, tint)

@Composable
fun IconDragHandle(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.DragHandle, "Reorder", modifier, tint)

@Composable
fun IconEmojiEvents(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.EmojiEvents, "Badges", modifier, tint)

@Composable
fun IconHighlightAlt(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Outlined.HighlightAlt, "Select", modifier, tint)
