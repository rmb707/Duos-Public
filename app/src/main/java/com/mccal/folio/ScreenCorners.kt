package com.mccal.folio

import android.view.RoundedCorner
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp

/**
 * Black rounded corners over Home, so the screen looks like the iPhone Duo concept's (issue #8). Drawn above Folio's
 * content, with touches passing straight through. Never smaller than the display's own corners.
 */
@Composable
internal fun RoundedScreenCorners(radius: Dp) {
    val view = LocalView.current
    val configuration = LocalConfiguration.current
    val hardware = remember(view, configuration) {
        val insets = view.rootWindowInsets
        listOf(RoundedCorner.POSITION_TOP_LEFT, RoundedCorner.POSITION_TOP_RIGHT, RoundedCorner.POSITION_BOTTOM_LEFT, RoundedCorner.POSITION_BOTTOM_RIGHT)
            .maxOfOrNull { insets?.getRoundedCorner(it)?.radius ?: 0 } ?: 0
    }
    Canvas(Modifier.fillMaxSize()) {
        val r = maxOf(radius.toPx(), hardware.toFloat()).coerceAtMost(size.minDimension / 2)
        val path = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height))
            addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(r)))
        }
        drawPath(path, Color.Black)
    }
}
