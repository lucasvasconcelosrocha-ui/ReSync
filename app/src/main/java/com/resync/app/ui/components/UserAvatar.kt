package com.resync.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.resync.app.ui.theme.DarkBg
import com.resync.app.ui.theme.DarkCardBorder

@Composable
fun UserAvatar(
    photoUrl: String?,
    displayName: String?,
    email: String?,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    borderColor: Color = DarkCardBorder,
    borderWidth: Dp = 1.dp,
    accentColor: Color = Color.White,
    onClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val fallbackInitial = remember(displayName, email) {
        val nameChar = displayName?.trim()?.firstOrNull()
        val emailChar = email?.trim()?.firstOrNull()
        (nameChar ?: emailChar ?: 'U').uppercaseChar()
    }

    val clickableModifier = if (onClick != null) {
        Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = ripple(bounded = true, color = accentColor),
            onClick = onClick
        )
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(DarkBg)
            .border(borderWidth, borderColor, CircleShape)
            .then(clickableModifier),
        contentAlignment = Alignment.Center
    ) {
        if (!photoUrl.isNullOrBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(photoUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = displayName ?: email ?: "Foto de perfil",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            val fontSize = (size.value * 0.42f).coerceAtLeast(10f).sp
            Text(
                text = fallbackInitial.toString(),
                fontWeight = FontWeight.Bold,
                color = accentColor,
                fontSize = fontSize
            )
        }
    }
}
