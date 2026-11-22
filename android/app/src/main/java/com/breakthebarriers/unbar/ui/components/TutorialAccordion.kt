package com.breakthebarriers.unbar.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.breakthebarriers.unbar.R
import com.breakthebarriers.unbar.ui.theme.UnbarOrange

// One tutorial topic: a title row that expands to show one short paragraph.
data class TutorialTopic(
    @StringRes val titleRes: Int,
    @StringRes val bodyRes: Int
)

// A group of topics under one heading; titleRes is null for an ungrouped list
// (the Servers tutorial has no groups).
data class TutorialGroup(
    @StringRes val titleRes: Int?,
    val topics: List<TutorialTopic>
)

// Two-level accordion: only the header row shows first; tapping it
// reveals the topic rows; tapping a topic row shows its paragraph. Collapsed
// by default, so it takes a single row until the user opens it.
// The header title is a parameter: Home passes the Guide title
// (R.string.guide_title); Servers/Settings use the Tutorial title.
@Composable
fun TutorialAccordion(
    groups: List<TutorialGroup>,
    modifier: Modifier = Modifier,
    @StringRes titleRes: Int = R.string.tutorial_title
) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Rounded.MenuBook,
                    contentDescription = null,
                    tint = UnbarOrange,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = stringResource(titleRes),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = UnbarOrange,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                    contentDescription = null,
                    tint = UnbarOrange
                )
            }
            if (expanded) {
                Spacer(modifier = Modifier.height(6.dp))
                for (group in groups) {
                    val groupTitleRes = group.titleRes
                    if (groupTitleRes != null) {
                        Text(
                            text = stringResource(groupTitleRes),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = UnbarOrange,
                            modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
                        )
                    }
                    for (topic in group.topics) {
                        TutorialTopicRow(topic = topic)
                    }
                }
            }
        }
    }
}

@Composable
private fun TutorialTopicRow(topic: TutorialTopic) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(topic.titleRes),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Icon(
                if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
        if (expanded) {
            Text(
                text = stringResource(topic.bodyRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 10.dp)
            )
        }
    }
}
