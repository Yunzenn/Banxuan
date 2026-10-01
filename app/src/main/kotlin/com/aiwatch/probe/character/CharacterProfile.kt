package com.aiwatch.probe.character

/**
 * Describes who the companion is. The product must never hard-code a specific character: the UI reads
 * everything it shows from here, so swapping [displayName] / [avatar] / [voice] is a data change.
 */
data class CharacterProfile(
    val id: String,
    val displayName: String,
    val subtitle: String,
    val avatar: AvatarSource,
    val voice: VoiceProfile,
    val personality: PersonalityProfile,
) {
    companion object {
        /**
         * Development default. The customer-supplied Mahiro assets are explicitly NOT shipped (P0-1
         * constraint 14) and the still that accompanies them is a watermarked promo card, so the
         * packaged build uses an original generated Daylight illustration. A clean still dropped into
         * `app/src/main/assets/character/` (gitignored) takes over automatically at dev time.
         */
        const val DEV_DISPLAY_NAME = "小星"

        fun developmentDefault(): CharacterProfile = CharacterProfile(
            id = "dev-companion",
            displayName = DEV_DISPLAY_NAME,
            subtitle = "在这里陪着你",
            avatar = AvatarSource.StaticImage(
                assetPath = "character/stage.png",
                placeholderLabel = DEV_DISPLAY_NAME,
            ),
            voice = VoiceProfile(
                style = VoiceStyle.SWEET_SOFT,
                presetId = "sweet_soft",
                displayName = "温柔",
            ),
            personality = PersonalityProfile(
                presetId = "gentle_companion",
                displayName = "温柔陪伴",
                shortDescription = "轻声回应，不催促",
            ),
        )
    }
}

/** How the character is drawn. Only [StaticImage] exists in P0-1; the rest are reserved seams. */
sealed interface AvatarSource {
    data class StaticImage(val assetPath: String, val placeholderLabel: String) : AvatarSource
}

/**
 * Descriptive presets only. No real person's voice identity is reproduced or referenced here, and the
 * brief requires that the formal voice ids stay descriptive rather than named individuals.
 */
enum class VoiceStyle { SWEET_SOFT, BRIGHT, GENTLE, CALM }

data class VoiceProfile(
    val style: VoiceStyle,
    val presetId: String,
    val displayName: String,
)

data class PersonalityProfile(
    val presetId: String,
    val displayName: String,
    val shortDescription: String,
)
