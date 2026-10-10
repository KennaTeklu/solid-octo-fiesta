package com.peakform.fitness.ui.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.*
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.peakform.fitness.MainActivity
import com.peakform.fitness.core.ProState
import com.peakform.fitness.core.StreakFire
import com.peakform.fitness.engine.Stats

/**
 * ProWidget — home-screen widget (native improvement NA2): streak fire, workout count,
 * next workout day; tap anywhere opens the app. Glance-based.
 */
class ProWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ProWidget
}

object ProWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        ProState.init(context)
        if (!ProState.initialized) {
            kotlinx.coroutines.runBlocking { ProState.loadAll() }
        }
        val streak = try { Stats.calculateStreak() } catch (e: Exception) { 0 }
        val fire = StreakFire.evaluate(streak, daysSinceLast(context))
        val total = ProState.data.workouts.count { it.isCompleted }
        val next = try { Stats.nextWorkoutDate() } catch (e: Exception) { "—" }

        provideContent {
            GlanceTheme {
                Column(
                    modifier = GlanceModifier.fillMaxSize().background(ColorProvider(Color(0xFF101418))).padding(12.dp)
                        .clickable(actionStartActivity<MainActivity>()),
                    verticalAlignment = Alignment.Vertical.CenterVertically,
                ) {
                    Text(
                        "${fire.emoji} $streak",
                        style = TextStyle(color = ColorProvider(Color(0xFFF59E0B)), fontSize = 22.sp, fontWeight = FontWeight.Bold),
                    )
                    Text(
                        "day streak",
                        style = TextStyle(color = ColorProvider(Color(0xFF9CA3AF)), fontSize = 11.sp),
                    )
                    Spacer(GlanceModifier.height(6.dp))
                    Text(
                        "$total workouts",
                        style = TextStyle(color = ColorProvider(Color(0xFFE5E7EB)), fontSize = 13.sp, fontWeight = FontWeight.Bold),
                    )
                    Text(
                        "Next: $next",
                        style = TextStyle(color = ColorProvider(Color(0xFF9CA3AF)), fontSize = 11.sp),
                        maxLines = 1,
                    )
                }
            }
        }
    }

    private fun daysSinceLast(context: Context): Int {
        val last = ProState.data.workouts.lastOrNull { it.isCompleted }?.date ?: return 99
        val ms = ProState.utcDayMillis(last)
        return if (ms <= 0) 99 else ((System.currentTimeMillis() - ms) / (24L * 3600 * 1000)).toInt()
    }
}
