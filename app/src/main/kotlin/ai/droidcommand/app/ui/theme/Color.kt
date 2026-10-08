package ai.droidcommand.app.ui.theme

import androidx.compose.ui.graphics.Color

// Dark theme, neutral accent — replaces Compose/Material3's baseline purple defaults
// (visible pre-change on every screen: SetToolScreen's filled chips, the Settings
// "Save" button). Accent is a desaturated teal, not a brand color; there is no brand
// palette defined anywhere in this repo today.

// Neutral surfaces — near-black, not pure black (keeps elevation visible via tonal
// overlay without relying on a shadow, which Compose dark themes render poorly).
val SurfaceDarkest = Color(0xFF101214)
val SurfaceDark = Color(0xFF17191C)
val SurfaceDarkElevated = Color(0xFF1F2227)
val SurfaceDarkElevated2 = Color(0xFF2A2E34)
val OutlineDark = Color(0xFF3A3F46)
val OnSurfaceDark = Color(0xFFE2E4E7)
val OnSurfaceVariantDark = Color(0xFFA9AFB8)

// Accent — teal, not purple. One hue used consistently for primary actions
// (Save/Launch buttons, selected chips, focus rings) across every screen.
val AccentTeal = Color(0xFF4FC3BE)
val AccentTealDark = Color(0xFF1C3B3A)
val OnAccentTeal = Color(0xFF00312E)

// Secondary — cool slate grey, used for less prominent selected state (e.g.
// unselected-but-focused chips) so not everything reads as "primary".
val SlateSecondary = Color(0xFF9AA7B4)
val SlateSecondaryContainer = Color(0xFF34404A)
val OnSlateSecondaryContainer = Color(0xFFD4DEE8)

// Error stays Material's standard red family — no reason to reinvent it, and the
// existing `MaterialTheme.colorScheme.error` usage in ChatScreen should stay legible.
val ErrorDark = Color(0xFFFFB4AB)
val OnErrorDark = Color(0xFF690005)
val ErrorContainerDark = Color(0xFF93000A)
val OnErrorContainerDark = Color(0xFFFFDAD6)
