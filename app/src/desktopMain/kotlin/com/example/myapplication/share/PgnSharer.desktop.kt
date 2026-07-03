package com.example.myapplication.share

import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.io.FileWriter

/**
 * Desktop [PgnSharer]. Opens a save [FileDialog] (native file chooser) defaulting to
 * [suggestedFileName], and writes the PGN to the chosen path. Falls back to no-op if the user
 * cancels. (Clipboard copy is a future enhancement; a file is the more useful desktop export and
 * matches "Save game" intent.)
 *
 * Runs on the EDT via the AWT frame; `FileDialog.LOAD`/`SAVE` are modal so this blocks until the
 * user picks a file or cancels.
 */
class DesktopPgnSharer : PgnSharer {
    override fun share(pgn: String, suggestedFileName: String) {
        val dialog = FileDialog(Frame(), "Save PGN", FileDialog.SAVE).apply {
            file = suggestedFileName
            isVisible = true
        }
        val selected = dialog.file ?: return  // user cancelled
        val dir = dialog.directory ?: return
        val target = File(dir, selected)
        FileWriter(target).use { it.write(pgn) }
    }
}

/** Factory mirroring `desktopBoard3DSupport()` — constructed at the desktop entry point. */
fun desktopPgnSharer(): PgnSharer = DesktopPgnSharer()
