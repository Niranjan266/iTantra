package com.itantra.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itantra.lang.LanguagePack
import com.itantra.session.SessionUiState

/**
 * Language selection, generated entirely from what is installed (PRD F-41).
 *
 * There is no list of languages in this file, and no `when` over language codes. The
 * chips come from whatever packs are on disk, labelled with the name each pack gives
 * itself — so a pack added in its own script shows up in that script without anyone
 * touching the UI.
 *
 * The acceptance test is PRD section 10.5: a teammate copies a folder onto the device,
 * and the new language appears here after a restart, having opened no `.kt` file.
 */
@Composable
fun LanguagePicker(
    state: SessionUiState,
    packs: List<LanguagePack>,
    onSelect: (LanguagePack) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Language", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(
                    if (packs.isEmpty()) "none installed"
                    else "${packs.size} installed",
                    fontSize = 11.sp,
                    color = Color.Gray,
                )
            }

            if (packs.isEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "No language packs found. Copy a pack folder into the app's " +
                        "languages directory and restart.",
                    fontSize = 12.sp,
                    color = Color.Gray,
                )
                return@Column
            }

            Spacer(Modifier.height(8.dp))

            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                packs.forEach { pack ->
                    FilterChip(
                        selected = state.selectedLangId == pack.id,
                        onClick = { onSelect(pack) },
                        enabled = !state.enginesLoading,
                        label = { Text(pack.displayName, fontSize = 12.sp) },
                    )
                }
            }

            if (state.enginesLoading) {
                Spacer(Modifier.height(6.dp))
                Text("Loading models…", fontSize = 11.sp, color = Color.Gray)
            }
        }
    }
}
