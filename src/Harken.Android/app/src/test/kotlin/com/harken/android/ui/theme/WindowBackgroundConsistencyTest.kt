package com.harken.android.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * `window_background` in res/values(-night)/colors.xml is painted by the OS before Compose
 * starts, so it can't read ProtoColors and has to be hand-duplicated. UI-009 and UI-024 both
 * let it drift from ProtoColors.screenBg, showing the wrong ground for a frame on cold start.
 * This test fails the build the next time someone re-palettes one side and forgets the other.
 */
class WindowBackgroundConsistencyTest {

    private fun hexOf(color: Color): String =
        String.format("#%06X", color.toArgb() and 0xFFFFFF)

    private fun windowBackgroundHex(resourceDir: String): String {
        val xml = File("src/main/res/$resourceDir/colors.xml").readText()
        val match = Regex("""window_background">(#[0-9A-Fa-f]{6})<""").find(xml)
            ?: error("window_background not found in $resourceDir/colors.xml")
        return match.groupValues[1].uppercase()
    }

    @Test
    fun `light window background matches ProtoLightColors screenBg`() {
        assertEquals(hexOf(ProtoLightColors.screenBg), windowBackgroundHex("values"))
    }

    @Test
    fun `dark window background matches ProtoDarkColors screenBg`() {
        assertEquals(hexOf(ProtoDarkColors.screenBg), windowBackgroundHex("values-night"))
    }
}
