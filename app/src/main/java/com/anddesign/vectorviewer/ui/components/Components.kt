package com.anddesign.vectorviewer.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anddesign.vectorviewer.render.ThumbnailCache

fun badgeColor(badge: String): Color = when (badge) {
    "AI" -> Color(0xFFFF9A00)
    "PDF" -> Color(0xFFE53935)
    "EPS" -> Color(0xFF8E24AA)
    "SVG" -> Color(0xFF43A047)
    "CDR" -> Color(0xFF00A651)
    "DXF" -> Color(0xFF1E88E5)
    "PLT" -> Color(0xFF6D4C41)
    else -> Color(0xFF757575)
}

@Composable
fun FormatBadge(badge: String, modifier: Modifier = Modifier) {
    Text(
        text = badge,
        color = Color.White,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(badgeColor(badge))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** Miniatura del documento, generada en segundo plano y cacheada. */
@Composable
fun DocThumbnail(uri: String, modifier: Modifier = Modifier, version: Long = 0L) {
    val context = LocalContext.current
    val bmp by produceState<Bitmap?>(ThumbnailCache.peek(uri, version), uri, version) {
        if (value == null) value = ThumbnailCache.load(context, uri, version)
    }
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFFF1F1F1)),
        contentAlignment = Alignment.Center,
    ) {
        val b = bmp
        if (b != null) {
            Image(
                bitmap = b.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(4.dp),
            )
        } else {
            Icon(Icons.Outlined.Draw, contentDescription = null, tint = Color(0xFFBDBDBD), modifier = Modifier.size(28.dp))
        }
    }
}

/** Píldora semitransparente para la información superpuesta en el visor. */
@Composable
fun Pill(text: String, modifier: Modifier = Modifier, warning: Boolean = false, onClick: (() -> Unit)? = null) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (warning) Color(0xE6FFB300) else Color(0xCC000000),
        contentColor = if (warning) Color.Black else Color.White,
        modifier = if (onClick != null) modifier.clip(RoundedCornerShape(50)).clickable(onClick = onClick) else modifier,
    ) {
        Text(text, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp))
    }
}
