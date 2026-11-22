package dev.darkpyonix.composerust.ui.platform

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// FR-82's diagnostic scene: the renderer's own, drawn by the off-screen tests and by the
// window checks of each platform (the Windows binary's Fr82WindowsCheck), so both judge the
// same picture. No application API reaches it.

/** The scene's size, in dp. */
internal const val FR82_SCENE_WIDTH_DP = 160
internal const val FR82_SCENE_HEIGHT_DP = 120

/**
 * HiDPI diagnostic scene: on a white background, (a) a 1dp vertical black bar at x = 10dp,
 * (b) a 1dp horizontal black bar at y = 10dp, (c) a 40dp-diameter black circle, (d) a black
 * square clipped with a 12dp corner radius, and (e) 16sp black "Hg".
 */
@Composable
internal fun Fr82DiagnosticScene() {
    Box(
        Modifier
            .size(FR82_SCENE_WIDTH_DP.dp, FR82_SCENE_HEIGHT_DP.dp)
            .background(Color.White),
    ) {
        // (a) 1dp vertical black bar at x = 10dp
        Box(
            Modifier
                .offset(x = 10.dp, y = 20.dp)
                .size(width = 1.dp, height = 80.dp)
                .background(Color.Black),
        )
        // (b) 1dp horizontal black bar at y = 10dp
        Box(
            Modifier
                .offset(x = 20.dp, y = 10.dp)
                .size(width = 120.dp, height = 1.dp)
                .background(Color.Black),
        )
        // (c) 40dp-diameter black circle
        Box(
            Modifier
                .offset(x = 24.dp, y = 24.dp)
                .size(40.dp)
                .clip(CircleShape)
                .background(Color.Black),
        )
        // (d) 12dp rounded-clip black square
        Box(
            Modifier
                .offset(x = 76.dp, y = 24.dp)
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black),
        )
        // (e) 16sp black "Hg"
        Text(
            text = "Hg",
            color = Color.Black,
            fontSize = 16.sp,
            modifier = Modifier.offset(x = 24.dp, y = 76.dp),
        )
    }
}
