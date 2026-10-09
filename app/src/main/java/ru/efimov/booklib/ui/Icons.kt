package ru.efimov.booklib.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Тонкие контурные иконки в духе iOS (те же, что в макетах). Цвет задаёт `tint` у Icon. */
object AppIcons {
    private fun stroke(name: String, vararg paths: String, width: Float = 2f): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach {
                addPath(
                    addPathNodes(it),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = width,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    private fun rect(x: Float, y: Float) =
        "M${x + 2},${y}h2.5a2,2 0 0 1 2,2v2.5a2,2 0 0 1 -2,2H${x + 2}a2,2 0 0 1 -2,-2V${y + 2}a2,2 0 0 1 2,-2z"

    val Library = stroke(
        "library",
        "M12,6.5C10,5 7,4.5 3.5,4.5v14c3.5,0 6.5,0.5 8.5,2 2,-1.5 5,-2 8.5,-2v-14C17,4.5 14,5 12,6.5z",
        "M12,6.5v14",
    )
    val Apps = stroke("apps", rect(4f, 4f), rect(13.5f, 4f), rect(4f, 13.5f), rect(13.5f, 13.5f))
    val Folder = stroke("folder", "M3.5,7.5a2,2 0 0 1 2,-2h4l2,2h7a2,2 0 0 1 2,2v8a2,2 0 0 1 -2,2h-13a2,2 0 0 1 -2,-2z")
    val File = stroke("file", "M14,3H7a2,2 0 0 0 -2,2v14a2,2 0 0 0 2,2h10a2,2 0 0 0 2,-2V8z", "M14,3v5h5")
    val Settings = stroke(
        "settings",
        "M15,12a3,3 0 1 1 -6,0a3,3 0 1 1 6,0z",
        "M19.4,15a1.65,1.65 0 0 0 0.33,1.82l0.06,0.06a2,2 0 0 1 0,2.83a2,2 0 0 1 -2.83,0l-0.06,-0.06a1.65,1.65 0 0 0 -1.82,-0.33a1.65,1.65 0 0 0 -1,1.51V21a2,2 0 0 1 -2,2a2,2 0 0 1 -2,-2v-0.09A1.65,1.65 0 0 0 9,19.4a1.65,1.65 0 0 0 -1.82,0.33l-0.06,0.06a2,2 0 0 1 -2.83,0a2,2 0 0 1 0,-2.83l0.06,-0.06a1.65,1.65 0 0 0 0.33,-1.82a1.65,1.65 0 0 0 -1.51,-1H3a2,2 0 0 1 -2,-2a2,2 0 0 1 2,-2h0.09A1.65,1.65 0 0 0 4.6,9a1.65,1.65 0 0 0 -0.33,-1.82l-0.06,-0.06a2,2 0 0 1 0,-2.83a2,2 0 0 1 2.83,0l0.06,0.06a1.65,1.65 0 0 0 1.82,0.33H9a1.65,1.65 0 0 0 1,-1.51V3a2,2 0 0 1 2,-2a2,2 0 0 1 2,2v0.09a1.65,1.65 0 0 0 1,1.51a1.65,1.65 0 0 0 1.82,-0.33l0.06,-0.06a2,2 0 0 1 2.83,0a2,2 0 0 1 0,2.83l-0.06,0.06a1.65,1.65 0 0 0 -0.33,1.82V9a1.65,1.65 0 0 0 1.51,1H21a2,2 0 0 1 2,2a2,2 0 0 1 -2,2h-0.09a1.65,1.65 0 0 0 -1.51,1z",
        width = 1.8f,
    )
    val Image = stroke(
        "image",
        "M5,4h14a2,2 0 0 1 2,2v12a2,2 0 0 1 -2,2H5a2,2 0 0 1 -2,-2V6a2,2 0 0 1 2,-2z",
        "M3,16l5,-5 4,4 3,-3 6,6",
        "M10,8.5a1.5,1.5 0 1 1 -3,0a1.5,1.5 0 1 1 3,0z",
    )
    /** «Убрать из недавних»: часы, перечёркнутые линией. */
    val HideRecent = stroke(
        "hide_recent",
        "M20,12a8,8 0 1 1 -16,0a8,8 0 1 1 16,0z",
        "M12,8v4l2.5,1.5",
        "M4,4l16,16",
    )
    val Search = stroke("search", "M18,11a7,7 0 1 1 -14,0a7,7 0 1 1 14,0z", "M20,20l-3.5,-3.5", width = 2.2f)
    val Shelf = stroke("shelf", "M7,3.5h10a1,1 0 0 1 1,1v16l-6,-3.5 -6,3.5v-16a1,1 0 0 1 1,-1z")
    val Stats = stroke("stats", "M5,20v-7M12,20V4M19,20v-11", width = 2.4f)
    /** Ползунки — «вид, сортировка и фильтры». */
    val Filter = stroke(
        "filter",
        "M4,7h9M17,7h3M4,17h3M11,17h9",
        "M17,7a2,2 0 1 1 -4,0a2,2 0 1 1 4,0z",
        "M11,17a2,2 0 1 1 -4,0a2,2 0 1 1 4,0z",
    )
    val Sort = stroke("sort", "M4,7h16M4,12h11M4,17h6")
    val Grid = stroke("grid", rect(4f, 4f), rect(13.5f, 4f), rect(4f, 13.5f), rect(13.5f, 13.5f))
    val ListView = stroke("list", "M9,6h11M9,12h11M9,18h11M4.5,6h0.01M4.5,12h0.01M4.5,18h0.01", width = 2.2f)
    val Back = stroke("back", "M15,5l-7,7 7,7")
    val Close = stroke("close", "M6,6l12,12M18,6L6,18")
    val Chevron = stroke("chevron", "M7,10l5,5 5,-5")
}
