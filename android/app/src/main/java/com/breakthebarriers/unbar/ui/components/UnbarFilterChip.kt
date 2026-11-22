package com.breakthebarriers.unbar.ui.components

import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.breakthebarriers.unbar.ui.theme.UnbarOrange

/**
 * A FilterChip whose selected state uses the bold brand orange
 * (UnbarOrange) with white text, matching the Connect button —
 * instead of the pale secondaryContainer tint the Material default
 * uses. The unselected state is left at the Material default
 * (transparent with an outline).
 *
 * Used for every option pill in the app (connection mode, MTU,
 * resolvers, language, theme, per-app routing) so the selected
 * option reads as bold orange everywhere, in both themes.
 */
@Composable
fun UnbarFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = label,
        modifier = modifier,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = UnbarOrange,
            selectedLabelColor = Color.White
        )
    )
}
