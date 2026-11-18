package com.clipmax.app

/**
 * Pure JVM specification for ClipMax's redesigned pill-card Settings panel,
 * neutral-dark color harmonization, and Classic theme preservation.
 */
internal object SettingsPanelSpec {

    const val PREF_FILE = "clipmax_theme_prefs"
    const val PREF_APP_THEME = "app_theme"
    const val PREF_KEYBOARD_HEIGHT = "keyboard_height"
    const val PREF_ONE_HANDED = "one_handed_mode"
    const val PREF_AUTOCOMPLETE = "typing_autocomplete"
    const val PREF_AUTOCORRECT = "typing_autocorrect"
    const val PREF_SUGGESTIONS = "typing_suggestions"

    const val HEADER_HEIGHT_DP = 36f
    const val CARD_CORNER_RADIUS_DP = 12f
    const val PILL_CORNER_RADIUS_DP = 10f
    const val PILL_HEIGHT_DP = 32f
    const val ACTION_PILL_HEIGHT_DP = 38f
    const val PILL_GAP_DP = 6f
    const val CARD_GAP_DP = 8f
    const val OUTER_PADDING_HORIZONTAL_DP = 10f

    data class ChoiceOption<T>(
        val value: T,
        val label: String
    )

    data class BinarySettingDef(
        val prefKey: String,
        val title: String,
        val defaultValue: Boolean
    )

    val THEME_OPTIONS: List<ChoiceOption<String>> = listOf(
        ChoiceOption("graphite", "Graphite"),
        ChoiceOption("classic", "Classic")
    )

    val HEIGHT_OPTIONS: List<ChoiceOption<String>> = listOf(
        ChoiceOption("compact", "Compact"),
        ChoiceOption("normal", "Normal"),
        ChoiceOption("tall", "Tall")
    )

    val ONE_HANDED_OPTIONS: List<ChoiceOption<String>> = listOf(
        ChoiceOption("left", "Left"),
        ChoiceOption("off", "Off"),
        ChoiceOption("right", "Right")
    )

    val BINARY_ON_OFF_OPTIONS: List<ChoiceOption<Boolean>> = listOf(
        ChoiceOption(true, "On"),
        ChoiceOption(false, "Off")
    )

    val TYPING_SETTINGS: List<BinarySettingDef> = listOf(
        BinarySettingDef(PREF_AUTOCOMPLETE, "Autocomplete", true),
        BinarySettingDef(PREF_AUTOCORRECT, "Automatic Correction", true),
        BinarySettingDef(PREF_SUGGESTIONS, "Suggestions", true)
    )

    data class PillCardPalette(
        val isClassicTheme: Boolean,
        val isNightMode: Boolean,
        val panelBg: Int,
        val headerBg: Int,
        val dividerColor: Int,
        val cardBg: Int,
        val cardBorder: Int,
        val sectionTitleFg: Int,
        val itemLabelFg: Int,
        val subLabelFg: Int,
        val inactivePillBg: Int,
        val inactivePillBorder: Int,
        val inactivePillFg: Int,
        val activePillBg: Int,
        val activePillBorder: Int,
        val activePillFg: Int,
        val actionPillBg: Int,
        val actionPillBorder: Int,
        val actionPillFg: Int,
        val pillRipple: Int
    )

    data class PillVisualState(
        val bgColor: Int,
        val borderColor: Int,
        val textColor: Int,
        val isBold: Boolean
    )

    fun resolvePillCardPalette(palette: KeyboardLayoutSpec.ImeThemePalette): PillCardPalette {
        return when {
            palette.isClassicTheme && !palette.isNightMode -> PillCardPalette(
                isClassicTheme = true,
                isNightMode = false,
                panelBg = palette.colorBg,
                headerBg = palette.colorAltDeepBg,
                dividerColor = palette.colorKeyBorder,
                cardBg = 0xFFF7F9F8.toInt(),
                cardBorder = palette.colorKeyBorder,
                sectionTitleFg = palette.colorAccent,
                itemLabelFg = palette.colorKeyText,
                subLabelFg = palette.colorModText,
                inactivePillBg = palette.colorModBg,
                inactivePillBorder = palette.colorKeyBorder,
                inactivePillFg = palette.colorModText,
                activePillBg = palette.colorAccent,
                activePillBorder = palette.colorAccent,
                activePillFg = palette.colorAccentText,
                actionPillBg = palette.colorKeyBg,
                actionPillBorder = palette.colorKeyBorder,
                actionPillFg = palette.colorKeyText,
                pillRipple = 0x260D9488.toInt()
            )
            palette.isClassicTheme -> PillCardPalette(
                isClassicTheme = true,
                isNightMode = true,
                panelBg = palette.colorBg,
                headerBg = palette.colorAltDeepBg,
                dividerColor = palette.colorKeyBorder,
                cardBg = palette.colorModBg,
                cardBorder = palette.colorKeyBorder,
                sectionTitleFg = palette.colorAccent,
                itemLabelFg = palette.colorKeyText,
                subLabelFg = palette.colorModText,
                inactivePillBg = palette.colorKeyBg,
                inactivePillBorder = palette.colorKeyBorder,
                inactivePillFg = palette.colorModText,
                activePillBg = palette.colorAccent,
                activePillBorder = palette.colorAccent,
                activePillFg = palette.colorAccentText,
                actionPillBg = palette.colorKeyBg,
                actionPillBorder = palette.colorAccent,
                actionPillFg = palette.colorKeyText,
                pillRipple = 0x332DD4BF.toInt()
            )
            else -> PillCardPalette(
                isClassicTheme = false,
                isNightMode = true,
                panelBg = palette.colorBg,                   // #121316
                headerBg = palette.colorAltDeepBg,           // #181A1F
                dividerColor = palette.colorSnippetBorder,   // 8% white
                cardBg = palette.colorAltDeepBg,             // #181A1F
                cardBorder = palette.colorSnippetBorder,     // 8% white
                sectionTitleFg = palette.colorAltPrimaryText,// #F0F2F5
                itemLabelFg = palette.colorKeyText,          // #E1E3E6
                subLabelFg = palette.colorModText,           // #9EA4AE
                inactivePillBg = palette.colorAltPrimaryBg,  // #202328 matte charcoal
                inactivePillBorder = palette.colorKeyBorder, // 10% white
                inactivePillFg = palette.colorHintText,      // #8A909A muted grey
                activePillBg = 0xFF323741.toInt(),           // lifted neutral charcoal highlight
                activePillBorder = 0x4DFFFFFF.toInt(),       // ~30% white border emphasis
                activePillFg = palette.colorAltPrimaryText,  // #F0F2F5 crisp off-white
                actionPillBg = palette.colorKeyBg,           // #282C33
                actionPillBorder = 0x24FFFFFF.toInt(),       // subtle crisp border
                actionPillFg = palette.colorAltPrimaryText,  // #F0F2F5
                pillRipple = 0x24FFFFFF.toInt()
            )
        }
    }

    fun resolvePillVisualState(selected: Boolean, palette: PillCardPalette): PillVisualState {
        return if (selected) {
            PillVisualState(
                bgColor = palette.activePillBg,
                borderColor = palette.activePillBorder,
                textColor = palette.activePillFg,
                isBold = true
            )
        } else {
            PillVisualState(
                bgColor = palette.inactivePillBg,
                borderColor = palette.inactivePillBorder,
                textColor = palette.inactivePillFg,
                isBold = false
            )
        }
    }
}
