// app/src/main/java/com/domain/app/ui/components/navigation/AnthemosBottomBar.kt
package com.domain.app.ui.components.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.domain.app.ui.theme.AppIcons

@Composable
fun AnthemosBottomBar(
    currentPage: Int,
    onPageSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        tonalElevation = 3.dp,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Collect tab (left)
            NavigationBarItem(
                selected = currentPage == 0,
                onClick = { onPageSelected(0) },
                icon = {
                    Icon(
                        imageVector = AppIcons.Navigation.home,
                        contentDescription = "Collect"
                    )
                },
                label = { Text("Collect") },
                modifier = Modifier.weight(1f)
            )

            // Direct FAB (center)
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center
            ) {
                FloatingActionButton(
                    onClick = { onPageSelected(1) },
                    shape = CircleShape,
                    containerColor = if (currentPage == 1) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    contentColor = if (currentPage == 1) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onPrimary
                    },
                    elevation = FloatingActionButtonDefaults.elevation(
                        defaultElevation = if (currentPage == 1) 0.dp else 6.dp
                    )
                ) {
                    Icon(
                        imageVector = AppIcons.Communication.chat,
                        contentDescription = "Direct"
                    )
                }
            }

            // Reflect tab (right)
            NavigationBarItem(
                selected = currentPage == 2,
                onClick = { onPageSelected(2) },
                icon = {
                    Icon(
                        imageVector = AppIcons.Data.chart,
                        contentDescription = "Reflect"
                    )
                },
                label = { Text("Reflect") },
                modifier = Modifier.weight(1f)
            )
        }
    }
}
