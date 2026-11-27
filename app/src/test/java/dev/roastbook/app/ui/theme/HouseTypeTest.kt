package dev.roastbook.app.ui.theme

import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Test

/** The family's type scale (app-standard section 12, rule 24) where Roastbook's own roles carry it. */
class HouseTypeTest {
    @Test fun aRowTitleIs16Over22Medium() {
        assertEquals(16f, RowTitleStyle.fontSize.value, 0.01f)
        assertEquals(22f, RowTitleStyle.lineHeight.value, 0.01f)
        assertEquals(FontWeight.Medium, RowTitleStyle.fontWeight)
    }

    @Test fun supportingTextIs14Over20() {
        assertEquals(14f, RoastbookTypography.bodyMedium.fontSize.value, 0.01f)
        assertEquals(20f, RoastbookTypography.bodyMedium.lineHeight.value, 0.01f)
    }

    @Test fun finePrintIs12Over16() {
        assertEquals(12f, RoastbookTypography.labelSmall.fontSize.value, 0.01f)
        assertEquals(16f, RoastbookTypography.labelSmall.lineHeight.value, 0.01f)
    }
}
