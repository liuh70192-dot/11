package com.turrit.assistant.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.PeopleAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.turrit.assistant.ui.account.AccountScreen
import com.turrit.assistant.ui.group.GroupTaskScreen

private data class MainDestination(
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)

@Composable
fun TurritAssistantApp() {
    val destinations = listOf(
        MainDestination("账号管理", Icons.Outlined.PeopleAlt),
        MainDestination("群组任务", Icons.Outlined.Forum)
    )
    var selectedIndex by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                destinations.forEachIndexed { index, destination ->
                    NavigationBarItem(
                        selected = selectedIndex == index,
                        onClick = { selectedIndex = index },
                        icon = { Icon(destination.icon, contentDescription = destination.label) },
                        label = { Text(destination.label) }
                    )
                }
            }
        }
    ) { innerPadding ->
        when (selectedIndex) {
            0 -> AccountScreen(Modifier.padding(innerPadding))
            else -> GroupTaskScreen(Modifier.padding(innerPadding))
        }
    }
}
