package com.peakform.fitness.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.peakform.fitness.core.ShareText
import com.peakform.fitness.core.WorkoutRecord
import com.peakform.fitness.ui.components.P4Button
import com.peakform.fitness.ui.components.BtnStyle

/**
 * ShareSheet — QR modal + share text + system share + copy (legacy qrModal L6831–6843,
 * generateWorkoutShareText L46974–46990). QR renders LOCALLY via ZXing (no network).
 */
object Qr {
    fun encode(text: String, size: Int = 512): Bitmap {
        val hints = mapOf(
            com.google.zxing.EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.H,
            com.google.zxing.EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        val bits = com.google.zxing.qrcode.QRCodeWriter().encode(
            text, com.google.zxing.BarcodeFormat.QR_CODE, size, size, hints,
        )
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bmp.setPixel(x, y, if (bits.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        return bmp
    }
}

@Composable
fun ShareSheet(workout: WorkoutRecord, onDismiss: () -> Unit) {
    val c = LocalProColors.current
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val text = remember(workout.id) { ShareText.workoutShareText(workout) }
    val qr = remember(workout.id) { try { Qr.encode(ShareText.qrPayload(workout)) } catch (_: Exception) { null } }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.glass2,
        shape = RoundedCornerShape(24.dp),
        title = { Text("Share workout", style = ProType.cardTitle, color = c.text) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                qr?.let {
                    Image(it.asImageBitmap(), contentDescription = "Workout QR code", modifier = Modifier.size(190.dp).clip(RoundedCornerShape(12.dp)))
                    Spacer(Modifier.height(10.dp))
                }
                Text("📋 What will be copied", style = ProType.small, color = c.text3)
                Spacer(Modifier.height(4.dp))
                Text(text, style = ProType.small, color = c.text2, maxLines = 8)
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                P4Button("Copy", style = BtnStyle.SECONDARY, minHeight = 38, onClick = {
                    clipboard.setText(AnnotatedString(text))
                })
                P4Button("Share", style = BtnStyle.PRIMARY, minHeight = 38, onClick = {
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_TEXT, text)
                    }
                    ctx.startActivity(android.content.Intent.createChooser(send, "Share workout"))
                })
            }
        },
        dismissButton = { P4Button("Close", style = BtnStyle.GHOST, minHeight = 38, onClick = onDismiss) },
    )
}
