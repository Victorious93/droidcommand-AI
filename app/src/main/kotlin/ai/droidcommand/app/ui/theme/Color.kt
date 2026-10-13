package ai.droidcommand.app.ui.theme

import androidx.compose.ui.graphics.Color

// Visual language is a blue + darker ("deep ocean") dark theme, by explicit owner request.
// Near-black navy surfaces, a bright-blue primary accent, cyan labels and an indigo user
// bubble. The constant NAMES are kept stable across the app so every existing call site keeps
// compiling; only their hex values changed from the earlier green/OpenDroid palette.

// Surfaces — cool, blue-shifted near-blacks (darker than the previous palette).
val BackgroundDark = Color(0xFF070B14)
val SurfaceDark = Color(0xFF0B1220)
val CardDark = Color(0xFF111A2B)
val BorderDark = Color(0xFF22304A)
val NavBarDark = Color(0xFF0C1626)

// Text
val TextPrimary = Color(0xFFEAF1FB)
val TextSecondary = Color(0xFF8699B4)

// Accents. "Neon" is the bright blue used for thin marks, titles and selection; the calmer
// blue fills large buttons. (Names retained from the green era; values are now blue.)
val AccentNeonGreen = Color(0xFF3B9EFF)
val AccentGreenButton = Color(0xFF2563EB)
val AccentGreenContainer = Color(0xFF12294A)
val AccentCyan = Color(0xFF38E1FF)
val AccentPurple = Color(0xFF6C7BFF)
val UserBubbleDark = Color(0xFF1B2A52)
val AccentRed = Color(0xFFFF5A6E)
val OnAccentDark = Color(0xFF030912)
