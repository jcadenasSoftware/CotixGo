package com.cotixgo.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.cotixgo.app.data.AppRepository

object Routes {
    const val ONBOARDING = "onboarding"
    const val DASHBOARD = "dashboard"
    const val CLIENTS = "clients"
    const val CLIENT_NEW = "clients/new"
    const val CLIENT_EDIT = "clients/{id}/edit"
    const val CLIENT_DETAIL = "clients/{id}"
    fun clientEdit(id: String) = "clients/$id/edit"
    fun clientDetail(id: String) = "clients/$id"
}

@Composable
fun CotixGoApp(repo: AppRepository) {
    CotixGoTheme {
        val vm: AppViewModel = viewModel(factory = AppViewModel.factory(repo))
        val nav = rememberNavController()
        val user by vm.user.collectAsStateWithLifecycle()
        val bootstrapped by vm.bootstrapped.collectAsStateWithLifecycle()
        CotixGoNavHost(nav, vm, user?.name, bootstrapped)
    }
}

@Composable
private fun CotixGoNavHost(
    nav: NavHostController,
    vm: AppViewModel,
    userName: String?,
    bootstrapped: Boolean,
) {
    val backStack by nav.currentBackStackEntryAsState()
    val clients by vm.clients.collectAsStateWithLifecycle()
    val currentRoute = backStack?.destination?.route
    val showBar = currentRoute == Routes.DASHBOARD || currentRoute == Routes.CLIENTS

    Scaffold(
        bottomBar = {
            if (showBar) {
                NavigationBar {
                    NavigationBarItem(
                        selected = currentRoute == Routes.DASHBOARD,
                        onClick = {
                            nav.navigate(Routes.DASHBOARD) {
                                popUpTo(Routes.DASHBOARD) { inclusive = false }
                                launchSingleTop = true
                            }
                        },
                        icon = { Text("⌂") },
                        label = { Text("Inicio") },
                    )
                    NavigationBarItem(
                        selected = currentRoute == Routes.CLIENTS,
                        onClick = {
                            nav.navigate(Routes.CLIENTS) {
                                popUpTo(Routes.DASHBOARD)
                                launchSingleTop = true
                            }
                        },
                        icon = { Text("◎") },
                        label = { Text("Clientes") },
                    )
                }
            }
        },
    ) { padding ->
        // El NavHost (y su grafo) siempre está compuesto: la transición
        // onboarding→dashboard la dispara el cambio del Usuario persistido,
        // que recrea el grafo con startDestination=DASHBOARD. Sin esa
        // dependencia temporal entre navigate() y la composición del grafo.
        if (!bootstrapped) {
            Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            return@Scaffold
        }
        NavHost(
            navController = nav,
            startDestination = if (userName == null) Routes.ONBOARDING else Routes.DASHBOARD,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.ONBOARDING) {
                OnboardingScreen { name, profile ->
                    vm.completeOnboarding(name, profile)
                }
            }
            composable(Routes.DASHBOARD) {
                DashboardScreen(
                    userName = userName.orEmpty(),
                    onNewClient = { nav.navigate(Routes.CLIENT_NEW) },
                )
            }
            composable(Routes.CLIENTS) {
                ClientsScreen(
                    clients = clients,
                    onOpen = { nav.navigate(Routes.clientDetail(it)) },
                    onNew = { nav.navigate(Routes.CLIENT_NEW) },
                )
            }
            composable(Routes.CLIENT_NEW) {
                ClientFormScreen(
                    initial = null,
                    onSave = { input ->
                        vm.saveClient(input, existingId = null) { nav.popBackStack() }
                    },
                    onBack = { nav.popBackStack() },
                )
            }
            composable(
                Routes.CLIENT_EDIT,
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) { entry ->
                val id = entry.arguments?.getString("id") ?: return@composable
                ClientFormScreen(
                    initial = clients.firstOrNull { it.id == id },
                    onSave = { input ->
                        vm.saveClient(input, existingId = id) { nav.popBackStack() }
                    },
                    onBack = { nav.popBackStack() },
                )
            }
            composable(
                Routes.CLIENT_DETAIL,
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) { entry ->
                val id = entry.arguments?.getString("id") ?: return@composable
                ClientDetailScreen(
                    client = clients.firstOrNull { it.id == id },
                    onEdit = { nav.navigate(Routes.clientEdit(id)) },
                    onToggleArchive = { archived ->
                        vm.setClientArchived(id, archived)
                        nav.popBackStack()
                    },
                    onBack = { nav.popBackStack() },
                )
            }
        }
    }
}
