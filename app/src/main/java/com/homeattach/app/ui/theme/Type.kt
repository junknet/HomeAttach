package com.homeattach.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Deliberately plain system typography, tuned dense for a terminal-first tool: body sizes
// and line heights are pulled in slightly so lists and forms fit more rows per screen.
// Titles get a SemiBold weight so hierarchy reads without adding shadow or color noise.
val Typography = Typography().let { base ->
    base.copy(
        titleLarge = base.titleLarge.copy(
            fontWeight = FontWeight.SemiBold,
            fontSize = 20.sp,
            lineHeight = 26.sp,
        ),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = base.bodyLarge.copy(fontSize = 15.sp, lineHeight = 21.sp),
        bodyMedium = base.bodyMedium.copy(fontSize = 13.sp, lineHeight = 18.sp),
        bodySmall = base.bodySmall.copy(fontSize = 11.sp, lineHeight = 15.sp),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Medium),
        labelMedium = base.labelMedium.copy(fontWeight = FontWeight.Medium),
    )
}

// Monospace style used for command/cwd/key text so it visually reads as terminal content.
val MonoBody: TextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 13.sp,
    lineHeight = 18.sp,
)

// Smaller monospace for secondary terminal-ish metadata (paths, sizes, fingerprints).
val MonoCaption: TextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 11.sp,
    lineHeight = 15.sp,
)
