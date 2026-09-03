package app.aaps.pump.tandem.mobi.ui.actions

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import app.aaps.pump.tandem.R

private const val VIEWBOX = 128f
private const val WINDOW_LEFT = 46.17f
private const val WINDOW_TOP = 69.7f
private const val WINDOW_WIDTH = 33.23f
private const val WINDOW_HEIGHT = 21.69f

val InsulinFillColor = Color(0xFF03A9F4) // Material Light Blue 500

@Composable
fun MobiSilhouetteWithFill(
    fillFraction: Float,
    modifier: Modifier = Modifier,
    fillColor: Color = InsulinFillColor,
) {
    val clamped = fillFraction.coerceIn(0f, 1f)
    Box(modifier = modifier.aspectRatio(1f)) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val winLeft = size.width * (WINDOW_LEFT / VIEWBOX)
            val winTop = size.height * (WINDOW_TOP / VIEWBOX)
            val winWidth = size.width * (WINDOW_WIDTH / VIEWBOX)
            val winHeight = size.height * (WINDOW_HEIGHT / VIEWBOX)
            val fillHeight = winHeight * clamped
            drawRect(
                color = fillColor,
                topLeft = Offset(winLeft, winTop + (winHeight - fillHeight)),
                size = Size(winWidth, fillHeight),
            )
        }
        Image(
            painter = painterResource(R.drawable.mobi_with_clear_cartridge),
            contentDescription = null,
            modifier = Modifier.matchParentSize(),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun MobiSilhouetteWithFillPreview_Full() {
    MaterialTheme { MobiSilhouetteWithFill(fillFraction = 1.0f, modifier = Modifier) }
}

@Preview(showBackground = true)
@Composable
private fun MobiSilhouetteWithFillPreview_Half() {
    MaterialTheme { MobiSilhouetteWithFill(fillFraction = 0.5f, modifier = Modifier) }
}

@Preview(showBackground = true)
@Composable
private fun MobiSilhouetteWithFillPreview_Empty() {
    MaterialTheme { MobiSilhouetteWithFill(fillFraction = 0f, modifier = Modifier) }
}
