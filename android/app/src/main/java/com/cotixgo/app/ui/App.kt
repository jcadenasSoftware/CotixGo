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
import androidx.compose.runtime.setValue
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
    const val QUOTES = "quotes"
    const val QUOTE_NEW = "quotes/new"
    const val QUOTE_EDIT = "quotes/{id}/edit"
    const val QUOTE_DETAIL = "quotes/{id}"
    const val QUOTE_VERSION = "quotes/{id}/version/{v}"
    const val SETTINGS = "settings"
    const val SERVICES = "services"
    const val RETENTIONS = "retentions"
    const val PROFILE = "profile"
    fun clientEdit(id: String) = "clients/$id/edit"
    fun clientDetail(id: String) = "clients/$id"
    fun quoteEdit(id: String) = "quotes/$id/edit"
    fun quoteDetail(id: String) = "quotes/$id"
    fun quoteVersion(id: String, v: Int) = "quotes/$id/version/$v"
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
    val quotes by vm.quotes.collectAsStateWithLifecycle()
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val user by vm.user.collectAsStateWithLifecycle()
    val retentions by vm.retentions.collectAsStateWithLifecycle()
    val services by vm.services.collectAsStateWithLifecycle()
    val currentRoute = backStack?.destination?.route
    val showBar = currentRoute == Routes.DASHBOARD || currentRoute == Routes.CLIENTS || currentRoute == Routes.QUOTES

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
                    NavigationBarItem(
                        selected = currentRoute == Routes.QUOTES,
                        onClick = {
                            nav.navigate(Routes.QUOTES) {
                                popUpTo(Routes.DASHBOARD)
                                launchSingleTop = true
                            }
                        },
                        icon = { Text("▤") },
                        label = { Text("Cotiz.") },
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
                val today = java.time.LocalDate.now().toEpochDay()
                DashboardScreen(
                    userName = userName.orEmpty(),
                    expiringQuotes = quotes
                        .filter {
                            !it.archived && it.status == com.cotixgo.app.core.QuoteStatus.EMITIDA.name &&
                                com.cotixgo.app.core.effectiveQuoteStatus(
                                    com.cotixgo.app.core.QuoteStatus.valueOf(it.status),
                                    it.expiryDateEpochDay, today,
                                ) == com.cotixgo.app.core.QuoteStatus.EMITIDA
                        }
                        .sortedBy { it.expiryDateEpochDay }
                        .take(5),
                    onNewClient = { nav.navigate(Routes.CLIENT_NEW) },
                    onNewQuote = { nav.navigate(Routes.QUOTE_NEW) },
                    onOpenQuote = { nav.navigate(Routes.quoteDetail(it)) },
                    onSettings = { nav.navigate(Routes.SETTINGS) },
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
            composable(Routes.QUOTES) {
                QuotesListScreen(
                    quotes = quotes,
                    onOpen = { nav.navigate(Routes.quoteDetail(it)) },
                    onNew = { nav.navigate(Routes.QUOTE_NEW) },
                )
            }
            composable(Routes.QUOTE_NEW) {
                QuoteEditorScreen(
                    initial = null,
                    clients = clients,
                    profile = profiles.firstOrNull(),
                    user = user,
                    retentions = retentions,
                    services = services,
                    onSave = { input, toCatalog ->
                        toCatalog.forEach { vm.saveService(it, null) {} }
                        vm.saveQuote(input, null) { nav.popBackStack() }
                    },
                    onBack = { nav.popBackStack() },
                )
            }
            composable(
                Routes.QUOTE_EDIT,
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) { entry ->
                val id = entry.arguments?.getString("id") ?: return@composable
                val snapshot by androidx.compose.runtime.produceState<com.cotixgo.app.data.QuoteSnapshot?>(
                    initialValue = null, id,
                ) { value = vm.getQuoteSnapshot(id) }
                if (snapshot == null) return@composable
                QuoteEditorScreen(
                    initial = snapshot,
                    clients = clients,
                    profile = profiles.firstOrNull { it.id == snapshot?.profile?.id } ?: profiles.firstOrNull(),
                    user = user,
                    retentions = retentions,
                    services = services,
                    onSave = { input, toCatalog ->
                        toCatalog.forEach { vm.saveService(it, null) {} }
                        vm.saveQuote(input, id) { nav.popBackStack() }
                    },
                    onBack = { nav.popBackStack() },
                )
            }
            composable(
                Routes.QUOTE_DETAIL,
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) { entry ->
                val id = entry.arguments?.getString("id") ?: return@composable
                val quote by vm.observeQuote(id).collectAsStateWithLifecycle(null)
                val versions by vm.observeQuoteVersions(id).collectAsStateWithLifecycle(emptyList())
                val snapshot by androidx.compose.runtime.produceState<com.cotixgo.app.data.QuoteSnapshot?>(
                    initialValue = null, id, versions.size,
                ) { value = vm.getQuoteSnapshot(id) }
                var emitFailed by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
                QuoteDetailScreen(
                    quote = quote,
                    versions = versions,
                    latestSnapshot = snapshot,
                    emitFailed = emitFailed,
                    onEmit = { vm.emitQuote(id) { emitFailed = !it } },
                    onApprove = { vm.setQuoteStatus(id, com.cotixgo.app.core.QuoteStatus.APROBADA) {} },
                    onReject = { vm.setQuoteStatus(id, com.cotixgo.app.core.QuoteStatus.RECHAZADA) {} },
                    onRevertReject = { vm.setQuoteStatus(id, com.cotixgo.app.core.QuoteStatus.EMITIDA) {} },
                    onCancel = { vm.setQuoteStatus(id, com.cotixgo.app.core.QuoteStatus.CANCELADA) {} },
                    onRevalidate = { days ->
                        vm.revalidateQuote(id, days) { newId ->
                            newId?.let { nav.navigate(Routes.quoteDetail(it)) }
                        }
                    },
                    onEdit = { nav.navigate(Routes.quoteEdit(id)) },
                    onArchive = { archived -> vm.setQuoteArchived(id, archived) },
                    onOpenVersion = { v -> nav.navigate(Routes.quoteVersion(id, v)) },
                    onBack = { nav.popBackStack() },
                )
            }
            composable(
                Routes.QUOTE_VERSION,
                arguments = listOf(
                    navArgument("id") { type = NavType.StringType },
                    navArgument("v") { type = NavType.IntType },
                ),
            ) { entry ->
                val id = entry.arguments?.getString("id") ?: return@composable
                val v = entry.arguments?.getInt("v") ?: return@composable
                val versions by vm.observeQuoteVersions(id).collectAsStateWithLifecycle(emptyList())
                QuoteVersionScreen(
                    snapshot = versions.firstOrNull { it.version == v }
                        ?.let { com.cotixgo.app.data.SnapshotJson.decodeQuote(it.snapshotJson) },
                    onBack = { nav.popBackStack() },
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    user = user,
                    onCurrency = { vm.setMainCurrency(it) },
                    onServices = { nav.navigate(Routes.SERVICES) },
                    onRetentions = { nav.navigate(Routes.RETENTIONS) },
                    onProfile = { nav.navigate(Routes.PROFILE) },
                    onBack = { nav.popBackStack() },
                )
            }
            composable(Routes.SERVICES) {
                ServicesScreen(
                    services = services,
                    onSave = { input, id -> vm.saveService(input, id) {} },
                    onArchive = { id, archived -> vm.setServiceArchived(id, archived) },
                )
            }
            composable(Routes.RETENTIONS) {
                RetentionsScreen(
                    retentions = retentions,
                    onSave = { input, id -> vm.saveRetention(input, id) {} },
                    onArchive = { id, archived -> vm.setRetentionArchived(id, archived) },
                )
            }
            composable(Routes.PROFILE) {
                ProfileScreen(
                    profile = profiles.firstOrNull(),
                    onSave = { name, desc, info, vigencia ->
                        profiles.firstOrNull()?.let { p ->
                            vm.updateProfile(p.id, name, desc, info, vigencia) { nav.popBackStack() }
                        }
                    },
                    onBack = { nav.popBackStack() },
                )
            }
        }
    }
}
