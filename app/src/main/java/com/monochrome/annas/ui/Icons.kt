package com.monochrome.annas.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

object MonoIcons {
    val Search = Icons.Filled.Search
    val Back = Icons.Filled.ArrowBack
    val Close = Icons.Filled.Close
    val More = Icons.Filled.MoreVert
    val Add = Icons.Filled.Add
    val Settings = Icons.Filled.Settings

    val Download: ImageVector = ImageVector.Builder("Download", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.6f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(12f, 3f); lineTo(12f, 15f)
            moveTo(7f, 10f); lineTo(12f, 15f); lineTo(17f, 10f)
            moveTo(5f, 21f); lineTo(19f, 21f)
        }
    }.build()

    val Library: ImageVector = ImageVector.Builder("Library", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.6f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(4f, 5f); lineTo(4f, 19f)
            moveTo(8f, 5f); lineTo(8f, 19f)
            moveTo(12f, 6f); lineTo(20f, 4f); lineTo(20f, 18f); lineTo(12f, 20f); close()
        }
    }.build()

    val Open: ImageVector = ImageVector.Builder("Open", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.6f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(14f, 4f); lineTo(20f, 4f); lineTo(20f, 10f)
            moveTo(20f, 4f); lineTo(10f, 14f)
            moveTo(9f, 5f); lineTo(5f, 5f); lineTo(5f, 19f); lineTo(19f, 19f); lineTo(19f, 15f)
        }
    }.build()

    val Folder: ImageVector = ImageVector.Builder("Folder", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.6f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(3f, 6f); lineTo(9f, 6f); lineTo(11f, 8f); lineTo(21f, 8f); lineTo(21f, 19f); lineTo(3f, 19f); close()
        }
    }.build()

    val Trash: ImageVector = ImageVector.Builder("Trash", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.6f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(4f, 7f); lineTo(20f, 7f)
            moveTo(9f, 7f); lineTo(9f, 4f); lineTo(15f, 4f); lineTo(15f, 7f)
            moveTo(6f, 7f); lineTo(7f, 20f); lineTo(17f, 20f); lineTo(18f, 7f)
        }
    }.build()

    val Sort: ImageVector = ImageVector.Builder("Sort", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.6f, strokeLineCap = StrokeCap.Round) {
            moveTo(4f, 7f); lineTo(20f, 7f)
            moveTo(7f, 12f); lineTo(17f, 12f)
            moveTo(10f, 17f); lineTo(14f, 17f)
        }
    }.build()

    val Check: ImageVector = ImageVector.Builder("Check", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(5f, 12f); lineTo(10f, 17f); lineTo(20f, 7f)
        }
    }.build()

    val Bookmark: ImageVector = ImageVector.Builder("Bookmark", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black), stroke = null) {
            moveTo(6f, 3f); lineTo(18f, 3f); lineTo(18f, 21f); lineTo(12f, 17f); lineTo(6f, 21f); close()
        }
    }.build()

    val BookmarkOutline: ImageVector = ImageVector.Builder("BookmarkOutline", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.6f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(6f, 3f); lineTo(18f, 3f); lineTo(18f, 21f); lineTo(12f, 17f); lineTo(6f, 21f); close()
        }
    }.build()
}
