package com.resync.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// Bento Grid Shapes
val BentoCardShape = RoundedCornerShape(20.dp)
val BentoSubCardShape = RoundedCornerShape(14.dp)
val BentoPillShape = RoundedCornerShape(50.dp)
val BentoSmallShape = RoundedCornerShape(10.dp)

// Backward Compatibility Tokens
val StandardCardShape = BentoCardShape
val StandardInnerShape = BentoSubCardShape

val ReSyncShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = BentoSmallShape,
    medium = BentoSubCardShape,
    large = BentoCardShape,
    extraLarge = RoundedCornerShape(28.dp)
)
