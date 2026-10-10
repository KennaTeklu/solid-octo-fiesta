package com.peakform.fitness.ui

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * ProTileService — quick-settings tile (native improvement NA3): shows the streak,
 * tapping opens Pro straight into the Train tab.
 */
class ProTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        val streak = try {
            com.peakform.fitness.core.ProState.init(applicationContext)
            com.peakform.fitness.engine.Stats.calculateStreak()
        } catch (_: Exception) { 0 }
        tile.state = Tile.STATE_INACTIVE
        tile.label = "Pro — Train"
        tile.subtitle = "$streak day streak"
        tile.updateTile()
    }

    @Deprecated("Deprecated in Java")
    override fun onClick() {
        super.onClick()
        val intent = packageManager.getLaunchIntentForPackage("com.peakform.fitness") ?: return
        intent.putExtra("section", "workout")
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            val pi = android.app.PendingIntent.getActivity(
                this, 0, intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
