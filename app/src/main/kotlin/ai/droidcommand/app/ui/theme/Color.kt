package ai.droidcommand.app.ui.theme

import androidx.compose.ui.graphics.Color

// Blue-on-deep-navy dark palette. Token names keep the surface/text roles of the earlier
// OpenDroid-derived palette; only the values and the accent names changed.
// FleetThemeDefaults.APP in core-security copies these hexes for JVM fallback
// tests (background/surface/primary/secondary/onBackground/muted/danger/tile).
// Do not invent a stale token here.

// Surfaces
val BackgroundDark = Color(0xFF050912)
val SurfaceDark = Color(0xFF0A1220)
val CardDark = Color(0xFF111B2E)
val BorderDark = Color(0xFF243350)
val NavBarDark = Color(0xFF0B1A33)

// Text
val TextPrimary = Color(0xFFE8F0FC)
val TextSecondary = Color(0xFF8FA3C2)

// Accents. The bright blue is for thin marks, titles and selection; the deeper blue fills large buttons.
val AccentBlueBright = Color(0xFF5AA9FF)
val AccentBlue = Color(0xFF2F6FDB)
val AccentBlueContainer = Color(0xFF0F2A55)
val AccentCyan = Color(0xFF4FD1F5)
val AccentIndigo = Color(0xFF5B6CFF)
val UserBubbleDark = Color(0xFF1A2354)
val AccentRed = Color(0xFFFF5A52)
val OnAccent = Color(0xFFFFFFFF)
val OnAccentDark = Color(0xFF04101F)

// Status colors stay green so success/failure remain distinguishable from the blue brand accent.
val AccentSuccess = Color(0xFF3DDC84)
