package dev.roastbook.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.roastbook.app.R

private fun bricolage(weight: Int) = Font(
    R.font.bricolage_grotesque, FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight), FontVariation.Setting("opsz", 24f)),
)

private fun figtree(weight: Int, italic: Boolean = false) = Font(
    if (italic) R.font.figtree_italic else R.font.figtree, FontWeight(weight),
    style = if (italic) FontStyle.Italic else FontStyle.Normal,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** Headings and titles: Bricolage Grotesque, bundled as a variable font. */
val Display = FontFamily(bricolage(400), bricolage(700), bricolage(800))
/** Body and label text: Figtree, bundled as a variable font, with italics. */
val Text = FontFamily(figtree(400), figtree(500), figtree(600), figtree(700), figtree(400, italic = true), figtree(500, italic = true))

/** The app's type scale: Display for headlines and large titles, Text for everything else. */
val RoastbookTypography = Typography(
    headlineMedium = TextStyle(fontFamily = Display, fontWeight = FontWeight.ExtraBold, fontSize = 30.sp, lineHeight = 32.sp),
    headlineSmall = TextStyle(fontFamily = Display, fontWeight = FontWeight.ExtraBold, fontSize = 26.sp, lineHeight = 30.sp),
    titleLarge = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 17.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = Text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp),
    bodyLarge = TextStyle(fontFamily = Text, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = Text, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Text, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = Text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp),
    labelMedium = TextStyle(fontFamily = Text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = Text, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
)

/** A settings or About row's title: Figtree Medium 16/22, the family's row title. */
val RowTitleStyle = TextStyle(fontFamily = Text, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp)
/** Tasting-notes line: Figtree italic, 13.5/18. */
val NotesStyle = TextStyle(fontFamily = Text, fontWeight = FontWeight.Normal, fontStyle = FontStyle.Italic, fontSize = 13.5.sp, lineHeight = 18.sp)
/** Overall number in the row's rating column. */
val RatingStyle = TextStyle(fontFamily = Display, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, lineHeight = 22.sp)
