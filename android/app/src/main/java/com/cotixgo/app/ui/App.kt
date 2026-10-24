package com.cotixgo.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
import com.cotixgo.app.data.AuthRepository
import com.cotixgo.app.data.SyncRepository

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
    const val JOBS = "jobs"
    const val JOB_NEW = "jobs/new?client={clientId}&quote={quoteId}"
    const val JOB_EDIT = "jobs/{id}/edit"
    const val JOB_DETAIL = "jobs/{id}"
    const val REPORTS = "reports"
    const val REPORT_NEW = "reports/new?job={jobId}"
    const val REPORT_EDIT = "reports/{id}/edit"
    const val REPORT_DETAIL = "reports/{id}"
    const val REPORT_VERSION = "reports/{id}/version/{v}"
    const val COLLECTIONS = "collections"
    const val COLLECTION_NEW = "collections/new"
    const val COLLECTION_EDIT = "collections/{id}/edit"
    const val COLLECTION_DETAIL = "collections/{id}"
    const val CONSOLIDATED_NEW = "consolidateds/new"
    const val CONSOLIDATED_EDIT = "consolidateds/{id}/edit"
    const val CONSOLIDATED_DETAIL = "consolidateds/{id}"
    const val PAYMENT_NEW = "payments/new"
    const val PAYMENT_DETAIL = "payments/{id}"
    // F8 — Inventario y Compras
    const val INVENTORY = "inventory"
    const val MATERIAL_DETAIL = "materials/{id}"
    const val PURCHASE_NEW = "purchases/new"
    const val PURCHASE_EDIT = "purchases/{id}/edit"
    const val PURCHASE_DETAIL = "purchases/{id}"
    const val SETTINGS = "settings"
    const val SERVICES = "services"
    const val RETENTIONS = "retentions"
    const val PROFILE = "profile"
    const val ACCOUNT = "account"
    const val CONFLICTS = "conflicts"
    const val RESOLVE_VERSIONS = "conflicts/quote/{id}"
    const val RESOLVE_REPORT_VERSIONS = "conflicts/report/{id}"
    fun resolveVersions(id: String) = "conflicts/quote/$id"
    fun resolveReportVersions(id: String) = "conflicts/report/$id"
    fun clientEdit(id: String) = "clients/$id/edit"
    fun clientDetail(id: String) = "clients/$id"
    fun quoteEdit(id: String) = "quotes/$id/edit"
    fun quoteDetail(id: String) = "quotes/$id"
    fun quoteVersion(id: String, v: Int) = "quotes/$id/version/$v"
    fun jobNew(clientId: String? = null, quoteId: String? = null): String {
        val args = buildList {
            clientId?.let { add("client=$it") }
            quoteId?.let { add("quote=$it") }
        }
        return "jobs/new" + if (args.isEmpty()) "" else "?" + args.joinToString("&")
    }
    fun jobEdit(id: String) = "jobs/$id/edit"
    fun jobDetail(id: String) = "jobs/$id"
    fun reportNew(jobId: String? = null) =
        "reports/new" + (jobId?.let { "?job=$it" } ?: "")
    fun reportEdit(id: String) = "reports/$id/edit"
    fun reportDetail(id: String) = "reports/$id"
    fun reportVersion(id: String, v: Int) = "reports/$id/version/$v"
    fun collectionEdit(id: String) = "collections/$id/edit"
    fun collectionDetail(id: String) = "collections/$id"
    fun consolidatedEdit(id: String) = "consolidateds/$id/edit"
    fun consolidatedDetail(id: String) = "consolidateds/$id"
    fun paymentDetail(id: String) = "payments/$id"
    fun materialDetail(id: String) = "materials/$id"
    fun purchaseEdit(id: String) = "purchases/$id/edit"
    fun purchaseDetail(id: String) = "purchases/$id"
}

@Composable
fun CotixGoApp(repo: AppRepository, sync: SyncRepository, auth: AuthRepository) {
    CotixGoTheme {
        val vm: AppViewModel = viewModel(factory = AppViewModel.factory(repo, sync, auth))
        val nav = rememberNavController()
        val user by vm.user.collectAsStateWithLifecycle()
        val bootstrapped by vm.bootstrapped.collectAsStateWithLifecycle()
        CotixGoNavHost(nav, vm, user?.name, bootstrapped)
    }
}

/**
 * Fila del menú "Más": solo enlaza pantallas ya existentes; no agrega
 * funciones ni datos nuevos (referencia visual: navegación con "Más").
 */
@Composable
private fun MoreSheetRow(
    label: String,
    tileColor: Color,
    leading: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        color = Color.Transparent,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(
                modifier = Modifier.size(38.dp),
                shape = RoundedCornerShape(10.dp),
                color = tileColor,
            ) {
                Box(contentAlignment = Alignment.Center) { leading() }
            }
            Text(
                label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
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
    val jobs by vm.jobs.collectAsStateWithLifecycle()
    val reports by vm.reports.collectAsStateWithLifecycle()
    val collectionAccounts by vm.collectionAccounts.collectAsStateWithLifecycle()
    val collectionMethods by vm.collectionMethods.collectAsStateWithLifecycle()
    val payments by vm.payments.collectAsStateWithLifecycle()
    val consolidateds by vm.consolidateds.collectAsStateWithLifecycle()
    val activeMemberships by vm.activeMemberships.collectAsStateWithLifecycle()
    val inventory by vm.inventory.collectAsStateWithLifecycle()
    val materials by vm.materials.collectAsStateWithLifecycle()
    val purchases by vm.purchases.collectAsStateWithLifecycle()
    val currentRoute = backStack?.destination?.route
    val showBar = currentRoute == Routes.DASHBOARD || currentRoute == Routes.CLIENTS ||
        currentRoute == Routes.QUOTES || currentRoute == Routes.JOBS ||
        currentRoute == Routes.REPORTS || currentRoute == Routes.COLLECTIONS ||
        currentRoute == Routes.INVENTORY

    Scaffold(
        bottomBar = {
            if (showBar) {
                var showMore by remember { mutableStateOf(false) }
                // Referencia: Inicio, Clientes, Cotizaciones, Trabajos, Más.
                NavigationBar {
                    NavigationBarItem(
                        selected = currentRoute == Routes.DASHBOARD,
                        onClick = {
                            nav.navigate(Routes.DASHBOARD) {
                                popUpTo(Routes.DASHBOARD) { inclusive = false }
                                launchSingleTop = true
                            }
                        },
                        icon = { Icon(Icons.Filled.Home, contentDescription = null) },
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
                        icon = { Icon(Icons.Filled.Person, contentDescription = null) },
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
                        icon = {
                            Icon(
                                Icons.AutoMirrored.Filled.List,
                                contentDescription = null,
                            )
                        },
                        label = { Text("Cotiz.") },
                    )
                    NavigationBarItem(
                        selected = currentRoute == Routes.JOBS,
                        onClick = {
                            nav.navigate(Routes.JOBS) {
                                popUpTo(Routes.DASHBOARD)
                                launchSingleTop = true
                            }
                        },
                        icon = { Icon(Icons.Filled.Build, contentDescription = null) },
                        label = { Text("Trabajos") },
                    )
                    NavigationBarItem(
                        selected = currentRoute == Routes.REPORTS ||
                            currentRoute == Routes.COLLECTIONS ||
                            currentRoute == Routes.INVENTORY,
                        onClick = { showMore = true },
                        icon = { Icon(Icons.Filled.Menu, contentDescription = null) },
                        label = { Text("Más") },
                    )
                }
                // "Más" como bottom sheet: solo enlaza pantallas existentes.
                if (showMore) {
                    ModalBottomSheet(onDismissRequest = { showMore = false }) {
                        Column(Modifier.padding(bottom = 24.dp)) {
                            Text(
                                "Más",
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            MoreSheetRow(
                                label = "Informes",
                                tileColor = CotixGoBlueSoft,
                                leading = {
                                    Icon(Icons.Filled.Create, contentDescription = null,
                                        tint = CotixGoBlue, modifier = Modifier.size(20.dp))
                                },
                                onClick = {
                                    showMore = false
                                    nav.navigate(Routes.REPORTS) {
                                        popUpTo(Routes.DASHBOARD)
                                        launchSingleTop = true
                                    }
                                },
                            )
                            MoreSheetRow(
                                label = "Cobros",
                                tileColor = CotixGoAmberSoft,
                                leading = {
                                    Text(
                                        "$",
                                        color = MaterialTheme.colorScheme.tertiary,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                    )
                                },
                                onClick = {
                                    showMore = false
                                    nav.navigate(Routes.COLLECTIONS) {
                                        popUpTo(Routes.DASHBOARD)
                                        launchSingleTop = true
                                    }
                                },
                            )
                            MoreSheetRow(
                                label = "Inventario",
                                tileColor = MaterialTheme.colorScheme.secondaryContainer,
                                leading = {
                                    Icon(
                                        Icons.Filled.ShoppingCart,
                                        contentDescription = null,
                                        tint = CotixGoNavy,
                                        modifier = Modifier.size(20.dp),
                                    )
                                },
                                onClick = {
                                    showMore = false
                                    nav.navigate(Routes.INVENTORY) {
                                        popUpTo(Routes.DASHBOARD)
                                        launchSingleTop = true
                                    }
                                },
                            )
                            MoreSheetRow(
                                label = "Cuenta y sincronización",
                                tileColor = MaterialTheme.colorScheme.secondaryContainer,
                                leading = {
                                    Icon(
                                        Icons.Filled.AccountCircle,
                                        contentDescription = null,
                                        tint = CotixGoNavy,
                                        modifier = Modifier.size(20.dp),
                                    )
                                },
                                onClick = {
                                    showMore = false
                                    nav.navigate(Routes.ACCOUNT)
                                },
                            )
                            MoreSheetRow(
                                label = "Ajustes",
                                tileColor = MaterialTheme.colorScheme.secondaryContainer,
                                leading = {
                                    Icon(
                                        Icons.Filled.Settings,
                                        contentDescription = null,
                                        tint = CotixGoNavy,
                                        modifier = Modifier.size(20.dp),
                                    )
                                },
                                onClick = {
                                    showMore = false
                                    nav.navigate(Routes.SETTINGS)
                                },
                            )
                        }
                    }
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
                val pending by vm.pendingOps.collectAsStateWithLifecycle()
                val pausedCount by vm.pausedOps.collectAsStateWithLifecycle()
                val boundEmail by vm.accountEmail.collectAsStateWithLifecycle()
                DashboardScreen(
                    userName = userName.orEmpty(),
                    loading = !bootstrapped,
                    items = com.cotixgo.app.core.dashboardItems(
                        quotes, jobs, reports, collectionAccounts, today,
                    ),
                    todayEpochDay = today,
                    syncPending = pending,
                    syncPaused = pausedCount,
                    boundEmail = boundEmail,
                    onNewClient = { nav.navigate(Routes.CLIENT_NEW) },
                    onNewQuote = { nav.navigate(Routes.QUOTE_NEW) },
                    onNewJob = { nav.navigate(Routes.jobNew()) },
                    onNewAccount = { nav.navigate(Routes.COLLECTION_NEW) },
                    onOpenQuote = { nav.navigate(Routes.quoteDetail(it)) },
                    onOpenJob = { nav.navigate(Routes.jobDetail(it)) },
                    onOpenReport = { nav.navigate(Routes.reportDetail(it)) },
                    onOpenAccount = { nav.navigate(Routes.collectionDetail(it)) },
                    onSyncAttention = { nav.navigate(Routes.CONFLICTS) },
                    onSettings = { nav.navigate(Routes.SETTINGS) },
                    onAccount = { nav.navigate(Routes.ACCOUNT) },
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
                val client = clients.firstOrNull { it.id == id }
                // No componer el formulario hasta cargar el Cliente: una
                // primera composición vacía no es un estado definitivo y
                // guardar pisaría los datos persistidos con campos vacíos.
                if (client == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    ClientFormScreen(
                        initial = client,
                        onSave = { input ->
                            vm.saveClient(input, existingId = id) { nav.popBackStack() }
                        },
                        onBack = { nav.popBackStack() },
                    )
                }
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
                    onNewJob = { nav.navigate(Routes.jobNew(clientId = id)) },
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
                    onCreateJob = { nav.navigate(Routes.jobNew(quoteId = id)) },
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
            composable(Routes.JOBS) {
                JobsListScreen(
                    jobs = jobs,
                    onOpen = { nav.navigate(Routes.jobDetail(it)) },
                    onNew = { nav.navigate(Routes.jobNew()) },
                )
            }
            composable(
                Routes.JOB_NEW,
                arguments = listOf(
                    navArgument("clientId") {
                        type = NavType.StringType; nullable = true; defaultValue = null
                    },
                    navArgument("quoteId") {
                        type = NavType.StringType; nullable = true; defaultValue = null
                    },
                ),
            ) { entry ->
                val preClient = entry.arguments?.getString("clientId")
                val preQuote = entry.arguments?.getString("quoteId")
                JobFormScreen(
                    initial = null,
                    clients = clients,
                    profile = profiles.firstOrNull(),
                    linkableQuotes = quotes.filter { q ->
                        q.status == com.cotixgo.app.core.QuoteStatus.APROBADA.name &&
                            (preClient == null || q.clientId == preClient) &&
                            (preQuote == null || q.clientId == quotes.firstOrNull { it.id == preQuote }?.clientId)
                    },
                    preselectedClientId = preClient,
                    preselectedQuoteId = preQuote,
                    onSave = { input, onDone -> vm.saveJob(input, null, onDone) },
                    onSaved = { id ->
                        nav.navigate(Routes.jobDetail(id)) {
                            popUpTo(Routes.JOBS)
                        }
                    },
                    onBack = { nav.popBackStack() },
                )
            }
            composable(
                Routes.JOB_EDIT,
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) { entry ->
                val id = entry.arguments?.getString("id") ?: return@composable
                val job by vm.observeJob(id).collectAsStateWithLifecycle(null)
                // No componer el formulario hasta cargar el Trabajo: una
                // primera composición vacía no es un estado definitivo.
                if (job == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    JobFormScreen(
                        initial = job,
                        clients = clients,
                        profile = profiles.firstOrNull { it.id == job?.profileId } ?: profiles.firstOrNull(),
                        linkableQuotes = emptyList(),
                        onSave = { input, onDone -> vm.saveJob(input, id, onDone) },
                        onSaved = { nav.popBackStack() },
                        onBack = { nav.popBackStack() },
                    )
                }
            }
            composable(
                Routes.JOB_DETAIL,
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) { entry ->
                val id = entry.arguments?.getString("id") ?: return@composable
                val job by vm.observeJob(id).collectAsStateWithLifecycle(null)
                val activities by vm.observeJobActivities(id).collectAsStateWithLifecycle(emptyList())
                val materials by vm.observeJobMaterials(id).collectAsStateWithLifecycle(emptyList())
                val links by vm.observeJobQuotes(id).collectAsStateWithLifecycle(emptyList())
                val linkedReports by vm.observeReportsForJob(id).collectAsStateWithLifecycle(emptyList())
                var transitionError by androidx.compose.runtime.remember {
                    androidx.compose.runtime.mutableStateOf<String?>(null)
                }
                JobDetailScreen(
                    job = job,
                    activities = activities,
                    materials = materials,
                    links = links,
                    linkedQuotes = quotes,
                    linkedReports = linkedReports,
                    transitionError = transitionError,
                    onTransition = { target ->
                        vm.setJobStatus(id, target) { transitionError = it }
                    },
                    onEdit = { nav.navigate(Routes.jobEdit(id)) },
                    onArchive = { archived -> vm.setJobArchived(id, archived) },
                    onAddActivity = { vm.saveJobActivity(it) },
                    onActivityStatus = { aid, s -> vm.setJobActivityStatus(aid, s) },
                    onAddMaterial = { vm.saveJobMaterial(it) },
                    inventoryMaterials = inventory
                        .filter { !it.material.archived }
                        .map { it.material },
                    onLinkQuote = { qid -> vm.linkQuoteToJob(id, qid) },
                    linkableQuotes = quotes.filter { q ->
                        q.status == com.cotixgo.app.core.QuoteStatus.APROBADA.name &&
                            q.clientId == job?.clientId &&
                            links.none { it.quoteId == q.id }
                    },
                    onNewReport = { nav.navigate(Routes.reportNew(jobId = id)) },
                    onOpenReport = { nav.navigate(Routes.reportDetail(it)) },
                    onBack = { nav.popBackStack() },
                )
            }
            composable(Routes.REPORTS) {
                ReportsListScreen(
                    reports = reports,
                    onOpen = { nav.navigate(Routes.reportDetail(it)) },
                    onNew = { nav.navigate(Routes.reportNew()) },
                )
            }
            composable(
                Routes.REPORT_NEW,
                arguments = listOf(
                    navArgument("jobId") {
                        type = NavType.StringType; nullable = true; defaultValue = null
                    },
                ),
            ) { entry ->
                val preJob = entry.arguments?.getString("jobId")
                ReportFormScreen(
                    initial = null,
                    clients = clients,
                    profile = profiles.firstOrNull(),
                    jobs = jobs,
                    linkedJobs = emptyList(),
                    linkableJobs = emptyList(),
                    preselectedJobId = preJob,
                    onBuildContent = { ids, cb -> vm.buildReportContent(ids, cb) },
                    onSave = { input ->
                        vm.saveReport(input, null) { newId ->
                            if (newId != null) nav.navigate(Routes.reportDetail(newId)) {
                                popUpTo(Routes.REPORTS)
                            } else nav.popBackStack()
                        }
                    },
                    onLinkJob = {},
                    onBack = { nav.popBackStack() },
                )
            }
            composable(
                Routes.REPORT_EDIT,
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) { entry ->
                val id = entry.arguments?.getString("id") ?: return@composable
                val report by vm.observeReport(id).collectAsStateWithLifecycle(null)
                val reportLinks by vm.observeReportJobs(id)
                    .collectAsStateWithLifecycle(null as List<com.cotixgo.app.data.ReportJobEntity>?)
                val rep = report
                val loadedLinks = reportLinks
                // No componer el formulario hasta cargar Informe y vínculos:
                // una primera composición vacía no es un estado definitivo.
                if (rep == null || loadedLinks == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    return@composable
                }
                ReportFormScreen(
                    initial = rep,
                    clients = clients,
                    profile = profiles.firstOrNull { it.id == rep.profileId } ?: profiles.firstOrNull(),
                    jobs = jobs,
                    linkedJobs = loadedLinks.mapNotNull { l -> jobs.firstOrNull { it.id == l.jobId } },
                    linkableJobs = jobs.filter { j ->
                        j.clientId == rep.clientId && j.profileId == rep.profileId &&
                            !j.archived && loadedLinks.none { it.jobId == j.id }
                    },
                    onBuildContent = { ids, cb -> vm.buildReportContent(ids, cb) },
                    onSave = { input ->
                        vm.saveReport(input, id) { nav.popBackStack() }
                    },
                    onLinkJob = { jid -> vm.linkJobToReport(id, jid) {} },
                    onBack = { nav.popBackStack() },
                )
            }
            composable(
                Routes.REPORT_DETAIL,
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) { entry ->
                val id = entry.arguments?.getString("id") ?: return@composable
                val report by vm.observeReport(id).collectAsStateWithLifecycle(null)
                val reportLinks by vm.observeReportJobs(id).collectAsStateWithLifecycle(emptyList())
                val versions by vm.observeReportVersions(id).collectAsStateWithLifecycle(emptyList())
                var emitFailed by androidx.compose.runtime.remember {
                    androidx.compose.runtime.mutableStateOf(false)
                }
                ReportDetailScreen(
                    report = report,
                    linkedJobs = reportLinks.mapNotNull { l -> jobs.firstOrNull { it.id == l.jobId } },
                    versions = versions,
                    emitFailed = emitFailed,
                    onEmit = { vm.emitReport(id) { emitFailed = !it } },
                    onCorrect = { vm.correctReport(id) {} },
                    onEdit = { nav.navigate(Routes.reportEdit(id)) },
                    onArchive = { archived -> vm.setReportArchived(id, archived) },
                    onOpenVersion = { v -> nav.navigate(Routes.reportVersion(id, v)) },
                    onResolveVersions = { nav.navigate(Routes.resolveReportVersions(id)) },
                    onBack = { nav.popBackStack() },
                )
            }
            composable(
                Routes.REPORT_VERSION,
                arguments = listOf(
                    navArgument("id") { type = NavType.StringType },
                    navArgument("v") { type = NavType.IntType },
                ),
            ) { entry ->
                val id = entry.arguments?.getString("id") ?: return@composable
                val v = entry.arguments?.getInt("v") ?: return@composable
                val versions by vm.observeReportVersions(id).collectAsStateWithLifecycle(emptyList())
                ReportVersionScreen(
                    snapshot = versions.firstOrNull { it.version == v }
                        ?.let { com.cotixgo.app.data.SnapshotJson.decodeReport(it.snapshotJson) },
                    onBack = { nav.popBackStack() },
                )
            }
            composable(Routes.COLLECTIONS) {
                val effects by vm.destinationEffects.collectAsStateWithLifecycle()
                val latestOps by vm.latestOpStates.collectAsStateWithLifecycle()
                CollectionsScreen(
                    accounts = collectionAccounts,
                    consolidateds = consolidateds,
                    payments = payments,
                    methods = collectionMethods,
                    clients = clients,
                    accountsById = collectionAccounts.associateBy { it.id },
                    effects = effects,
                    latestOps = latestOps,
                    onOpenAccount = { nav.navigate(Routes.collectionDetail(it)) },
                    onOpenConsolidated = { nav.navigate(Routes.consolidatedDetail(it)) },
                    onOpenPayment = { nav.navigate(Routes.paymentDetail(it)) },
                    onNewAccount = { nav.navigate(Routes.COLLECTION_NEW) },
                    onNewConsolidated = { nav.navigate(Routes.CONSOLIDATED_NEW) },
                    onNewPayment = { nav.navigate(Routes.PAYMENT_NEW) },
                    onSaveMethod = { input, id -> vm.saveCollectionMethod(input, id) {} },
                    onSetMethodArchived = { id, archived -> vm.setCollectionMethodArchived(id, archived) },
                )
            }
            composable(Routes.CONSOLIDATED_NEW) {
                ConsolidatedEditorScreen(
                    initial = null,
                    initialMemberIds = emptyList(),
                    clients = clients,
                    accounts = collectionAccounts,
                    memberAccountIds = activeMemberships.keys,
                    onSave = { input, done ->
                        vm.saveConsolidated(input, null) { newId ->
                            if (newId != null) {
                                nav.navigate(Routes.consolidatedDetail(newId)) {
                                    popUpTo(Routes.COLLECTIONS)
                                }
                            }
                            done(newId)
                        }
                    },
                    onBack = { nav.popBackStack() },
                )
            }
            composable(
                Routes.CONSOLIDATED_EDIT,
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) { entry ->
                val id = entry.arguments?.getString("id") ?: return@composable
                val consolidated by vm.observeConsolidated(id).collectAsStateWithLifecycle(null)
                val members by vm.observeConsolidatedMembers(id)
                    .collectAsStateWithLifecycle(null as List<com.cotixgo.app.data.ConsolidatedCollectionAccountEntity>?)
                val cons = consolidated
                val loadedMembers = members
                // No componer el editor hasta cargar el Consolidado y sus
                // Cuentas miembro: una lista vacía previa a la primera
                // emisión de Room no es un estado definitivo y, recordada
                // como `initialMemberIds`, vaciaría los vínculos al
                // guardar.
                if (cons == null || loadedMembers == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    return@composable
                }
                ConsolidatedEditorScreen(
                    initial = cons,
                    initialMemberIds = loadedMembers.map { it.accountId },
                    clients = clients,
                    accounts = collectionAccounts,
                    memberAccountIds = activeMemberships
                        .filter { it.value != id }.keys,
                    onSave = { input, done ->
                        vm.saveConsolidated(input, id) { newId ->
                            if (newId != null) nav.popBackStack()
                            done(newId)
                        }
                    },
                    onBack = { nav.popBackStack() },
                )
            }
            composable(
                Routes.CONSOLIDATED_DETAIL,
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) { entry ->
                val id = entry.arguments?.getString("id") ?: return@composable
                val consolidated by vm.observeConsolidated(id).collectAsStateWithLifecycle(null)
                val members by vm.observeConsolidatedMembers(id).collectAsStateWithLifecycle(emptyList())
                val consPayments by vm.observePaymentsForConsolidated(id)
                    .collectAsStateWithLifecycle(emptyList())
                val consEffects by vm.destinationEffects.collectAsStateWithLifecycle()
                val accountsById = collectionAccounts.associateBy { it.id }
                ConsolidatedDetailScreen(
                    consolidated = consolidated,
                    memberAccounts = members.sortedBy { it.position }
                        .mapNotNull { accountsById[it.accountId] },
                    payments = consPayments,
                    effects = consEffects,
                    onEdit = { nav.navigate(Routes.consolidatedEdit(id)) },
                    onCancelConsolidated = { done -> vm.cancelConsolidated(id, done) },
                    onSetArchived = { archived -> vm.setConsolidatedArchived(id, archived) },
                    onOpenAccount = { nav.navigate(Routes.collectionDetail(it)) },
                    onOpenPayment = { nav.navigate(Routes.paymentDetail(it)) },
                    onBack = { nav.popBackStack() },
                )
            }
            composable(Routes.COLLECTION_NEW) {
                // Solo bloquean vínculos de Cuentas no canceladas; los de
                // una CANCELADA se conservan como historial pero liberan
                // el origen (docs/05 §179).
                val blockingJobLinks by vm.observeBlockingCollectionAccountJobs()
                    .collectAsStateWithLifecycle(emptyList())
                val blockingQuoteLinks by vm.observeBlockingCollectionAccountQuotes()
                    .collectAsStateWithLifecycle(emptyList())
                CollectionAccountEditorScreen(
                    initial = null,
                    initialItems = emptyList(),
                    initialJobIds = emptyList(),
                    initialQuoteIds = emptyList(),
                    clients = clients,
                    profile = profiles.firstOrNull(),
                    user = user,
                    jobs = jobs,
                    quotes = quotes,
                    takenJobIds = blockingJobLinks.map { it.jobId }.toSet(),
                    takenQuoteIds = blockingQuoteLinks.map { it.quoteId }.toSet(),
                    retentions = retentions,
                    initialRetentions = emptyList(),
                    onSave = { input ->
                        vm.saveCollectionAccount(input, null) { newId ->
                            if (newId != null) {
                                nav.navigate(Routes.collectionDetail(newId)) {
                                    popUpTo(Routes.COLLECTIONS)
                                }
                            }
                        }
                    },
                    onBack = { nav.popBackStack() },
                )
            }
            composable(
                Routes.COLLECTION_EDIT,
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) { entry ->
                val id = entry.arguments?.getString("id") ?: return@composable
                val account by vm.observeCollectionAccount(id).collectAsStateWithLifecycle(null)
                val items by vm.observeCollectionAccountItems(id)
                    .collectAsStateWithLifecycle(null as List<com.cotixgo.app.data.CollectionAccountItemEntity>?)
                val jobLinks by vm.observeCollectionAccountJobs(id)
                    .collectAsStateWithLifecycle(null as List<com.cotixgo.app.data.CollectionAccountJobEntity>?)
                val quoteLinks by vm.observeCollectionAccountQuotes(id)
                    .collectAsStateWithLifecycle(null as List<com.cotixgo.app.data.CollectionAccountQuoteEntity>?)
                val blockingJobLinks by vm.observeBlockingCollectionAccountJobs()
                    .collectAsStateWithLifecycle(emptyList())
                val blockingQuoteLinks by vm.observeBlockingCollectionAccountQuotes()
                    .collectAsStateWithLifecycle(emptyList())
                val acc = account
                val loadedItems = items
                val loadedJobLinks = jobLinks
                val loadedQuoteLinks = quoteLinks
                // No componer el editor hasta cargar Cuenta, líneas y
                // vínculos: las listas vacías previas a la primera emisión
                // de Room no son un estado definitivo y, recordadas como
                // `initial*`, borrarían líneas y vínculos al guardar
                // (saveCollectionAccount sustituye las colecciones).
                if (acc == null || loadedItems == null
                    || loadedJobLinks == null || loadedQuoteLinks == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    return@composable
                }
                val retNames = com.cotixgo.app.data.SnapshotJson.decodeRetentions(acc.retentionsSnapshotJson)
                    .map { it.name }
                CollectionAccountEditorScreen(
                    initial = acc,
                    initialItems = loadedItems,
                    initialJobIds = loadedJobLinks.map { it.jobId },
                    initialQuoteIds = loadedQuoteLinks.map { it.quoteId },
                    clients = clients,
                    profile = profiles.firstOrNull { it.id == acc.profileId } ?: profiles.firstOrNull(),
                    user = user,
                    jobs = jobs,
                    quotes = quotes,
                    takenJobIds = blockingJobLinks.filter { it.accountId != id }
                        .map { it.jobId }.toSet(),
                    takenQuoteIds = blockingQuoteLinks.filter { it.accountId != id }
                        .map { it.quoteId }.toSet(),
                    retentions = retentions,
                    initialRetentions = retNames,
                    onSave = { input ->
                        vm.saveCollectionAccount(input, id) { nav.popBackStack() }
                    },
                    onBack = { nav.popBackStack() },
                )
            }
            composable(
                Routes.COLLECTION_DETAIL,
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) { entry ->
                val id = entry.arguments?.getString("id") ?: return@composable
                val account by vm.observeCollectionAccount(id).collectAsStateWithLifecycle(null)
                val items by vm.observeCollectionAccountItems(id).collectAsStateWithLifecycle(emptyList())
                val jobLinks by vm.observeCollectionAccountJobs(id).collectAsStateWithLifecycle(emptyList())
                val quoteLinks by vm.observeCollectionAccountQuotes(id).collectAsStateWithLifecycle(emptyList())
                val assignments by vm.observeAccountAssignments(id).collectAsStateWithLifecycle(emptyList())
                val activeConsolidatedId = activeMemberships[id]
                val accountEffects by vm.destinationEffects.collectAsStateWithLifecycle()
                val accountOps by vm.latestOpStates.collectAsStateWithLifecycle()
                CollectionAccountDetailScreen(
                    account = account,
                    items = items,
                    jobLinks = jobLinks,
                    quoteLinks = quoteLinks,
                    jobs = jobs,
                    quotes = quotes,
                    assignments = assignments,
                    payments = payments,
                    activeConsolidated = consolidateds.firstOrNull { it.id == activeConsolidatedId },
                    effects = accountEffects,
                    latestOps = accountOps,
                    onEdit = { nav.navigate(Routes.collectionEdit(id)) },
                    onCancelAccount = { vm.cancelCollectionAccount(id) {} },
                    onSetArchived = { archived -> vm.setCollectionAccountArchived(id, archived) },
                    onOpenJob = { nav.navigate(Routes.jobDetail(it)) },
                    onOpenQuote = { nav.navigate(Routes.quoteDetail(it)) },
                    onOpenConsolidated = { nav.navigate(Routes.consolidatedDetail(it)) },
                    onBack = { nav.popBackStack() },
                )
            }
            composable(Routes.PAYMENT_NEW) {
                PaymentEditorScreen(
                    clients = clients,
                    accounts = collectionAccounts,
                    consolidateds = consolidateds,
                    memberAccountIds = activeMemberships.keys,
                    methods = collectionMethods,
                    onSave = { input ->
                        vm.savePayment(input) { ok ->
                            if (ok) nav.popBackStack()
                        }
                    },
                    onBack = { nav.popBackStack() },
                )
            }
            composable(
                Routes.PAYMENT_DETAIL,
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) { entry ->
                val id = entry.arguments?.getString("id") ?: return@composable
                val payment by vm.observePayment(id).collectAsStateWithLifecycle(null)
                val assignments by vm.observePaymentAssignments(id).collectAsStateWithLifecycle(emptyList())
                val adjustments by vm.observePaymentAdjustments(id).collectAsStateWithLifecycle(emptyList())
                val refunds by vm.observePaymentRefunds(id).collectAsStateWithLifecycle(emptyList())
                val accountsById = collectionAccounts.associateBy { it.id }
                val consById = consolidateds.associateBy { it.id }
                val payOps by vm.latestOpStates.collectAsStateWithLifecycle()
                PaymentDetailScreen(
                    payment = payment,
                    clientName = clients.firstOrNull { it.id == payment?.clientId }?.name ?: "",
                    methodName = collectionMethods.firstOrNull { it.id == payment?.collectionMethodId }?.name,
                    account = payment?.assignedAccountId?.let { accountsById[it] },
                    consolidated = payment?.assignedConsolidatedId?.let { consById[it] },
                    receivableAccounts = collectionAccounts.filter {
                        it.clientId == payment?.clientId && !it.archived &&
                            it.id != payment?.assignedAccountId &&
                            it.id !in activeMemberships.keys &&
                            it.status in setOf("PENDIENTE", "PARCIAL") &&
                            it.balance.toDoubleOrNull()?.let { b -> b > 0 } == true
                    },
                    receivableConsolidateds = consolidateds.filter {
                        it.clientId == payment?.clientId && !it.archived &&
                            it.id != payment?.assignedConsolidatedId &&
                            it.status in setOf("PENDIENTE", "PARCIAL") &&
                            it.balance.toDoubleOrNull()?.let { b -> b > 0 } == true
                    },
                    assignments = assignments,
                    adjustments = adjustments,
                    refunds = refunds,
                    accountsById = accountsById,
                    consolidatedsById = consById,
                    methods = collectionMethods,
                    latestOps = payOps,
                    onAssign = { kind, targetId, amount, reason, done ->
                        vm.assignPaymentToTarget(id, kind, targetId, amount, reason, done)
                    },
                    onUnassign = { reason, done -> vm.unassignPayment(id, reason, done) },
                    onReassign = { kind, targetId, reason, done ->
                        vm.reassignPaymentToTarget(id, kind, targetId, reason, done)
                    },
                    onCorrect = { eff, reason, done -> vm.correctPayment(id, eff, reason, done) },
                    onAnnul = { reason, done -> vm.annulPayment(id, reason, done) },
                    onRefund = { amt, day, mId, reason, done ->
                        vm.refundPayment(id, amt, day, mId, reason, done)
                    },
                    onBack = { nav.popBackStack() },
                )
            }
            // ---------- F8: Inventario y Compras ----------
            composable(Routes.INVENTORY) {
                val invOps by vm.latestOpStates.collectAsStateWithLifecycle()
                InventoryScreen(
                    inventory = inventory,
                    purchases = purchases.filter { !it.archived },
                    movements = vm.observeAllMovements(user?.id ?: "")
                        .collectAsStateWithLifecycle(emptyList()).value,
                    latestOps = invOps,
                    materialsById = materials.associateBy { it.id },
                    jobsById = jobs.associateBy { it.id },
                    activeMaterials = materials.filter { !it.archived },
                    onOpenMaterial = { nav.navigate(Routes.materialDetail(it)) },
                    onNewPurchase = { nav.navigate(Routes.PURCHASE_NEW) },
                    onOpenPurchase = { nav.navigate(Routes.purchaseDetail(it)) },
                    onSaveMaterial = { input, id -> vm.saveMaterial(input, id) {} },
                    onSaveMovement = { input, done -> vm.saveInventoryMovement(input, done) },
                )
            }
            composable(
                Routes.MATERIAL_DETAIL,
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) { entry ->
                val id = entry.arguments?.getString("id") ?: return@composable
                val material by vm.observeMaterial(id).collectAsStateWithLifecycle(null)
                val movements by vm.observeMovementsForMaterial(id)
                    .collectAsStateWithLifecycle(emptyList())
                var movementDialog by androidx.compose.runtime.remember {
                    androidx.compose.runtime.mutableStateOf(false)
                }
                if (movementDialog) {
                    MovementFormDialog(
                        materials = materials.filter { !it.archived },
                        jobs = jobs,
                        preselectedMaterialId = id,
                        onDismiss = { movementDialog = false },
                        onSave = { input, done ->
                            vm.saveInventoryMovement(input) { err ->
                                done(err)
                                if (err == null) movementDialog = false
                            }
                        },
                    )
                }
                MaterialDetailScreen(
                    material = material,
                    stock = inventory.firstOrNull { it.material.id == id },
                    movements = movements,
                    purchasesById = purchases.associateBy { it.id },
                    jobsById = jobs.associateBy { it.id },
                    onEdit = { input -> vm.saveMaterial(input, id) {} },
                    onArchive = { archived -> vm.setMaterialArchived(id, archived) },
                    onNewMovement = { movementDialog = true },
                    onOpenPurchase = { nav.navigate(Routes.purchaseDetail(it)) },
                    onBack = { nav.popBackStack() },
                )
            }
            composable(Routes.PURCHASE_NEW) {
                PurchaseEditorScreen(
                    initial = null,
                    initialItems = emptyList(),
                    materials = materials.filter { !it.archived },
                    confirmError = null,
                    onSave = { input, confirm, done ->
                        vm.savePurchase(input, null, confirm) { err ->
                            if (err == null) nav.popBackStack()
                            done(err)
                        }
                    },
                    onBack = { nav.popBackStack() },
                )
            }
            composable(
                Routes.PURCHASE_EDIT,
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) { entry ->
                val id = entry.arguments?.getString("id") ?: return@composable
                val purchase by vm.observePurchase(id).collectAsStateWithLifecycle(null)
                val items by vm.observePurchaseItems(id)
                    .collectAsStateWithLifecycle(null as List<com.cotixgo.app.data.PurchaseItemEntity>?)
                val p = purchase
                val loadedItems = items
                // No componer el editor hasta que Compra y líneas estén
                // cargadas: `emptyList()` previo a la primera emisión de
                // Room no es un estado definitivo y, recordado como
                // `initialItems`, haría que Guardar borrara las líneas
                // persistidas (savePurchase sustituye la lista completa).
                if (p == null || loadedItems == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    return@composable
                }
                PurchaseEditorScreen(
                    initial = p,
                    initialItems = loadedItems,
                    materials = materials.filter { !it.archived },
                    confirmError = null,
                    onSave = { input, confirm, done ->
                        vm.savePurchase(input, id, confirm) { err ->
                            if (err == null) nav.popBackStack()
                            done(err)
                        }
                    },
                    onBack = { nav.popBackStack() },
                )
            }
            composable(
                Routes.PURCHASE_DETAIL,
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) { entry ->
                val id = entry.arguments?.getString("id") ?: return@composable
                val purchase by vm.observePurchase(id).collectAsStateWithLifecycle(null)
                val items by vm.observePurchaseItems(id).collectAsStateWithLifecycle(emptyList())
                val movements by vm.observePurchaseMovements(id)
                    .collectAsStateWithLifecycle(emptyList())
                var actionError by androidx.compose.runtime.remember {
                    androidx.compose.runtime.mutableStateOf<String?>(null)
                }
                PurchaseDetailScreen(
                    purchase = purchase,
                    items = items,
                    movements = movements,
                    materialsById = materials.associateBy { it.id },
                    actionError = actionError,
                    onEdit = { nav.navigate(Routes.purchaseEdit(id)) },
                    onConfirm = { vm.confirmPurchase(id) { actionError = it } },
                    onAnnul = { vm.annulPurchase(id) { actionError = it } },
                    onArchive = { archived -> vm.setPurchaseArchived(id, archived) },
                    onBack = { nav.popBackStack() },
                )
            }
            composable(Routes.SETTINGS) {
                val ctx = androidx.compose.ui.platform.LocalContext.current
                var appLockEnabled by androidx.compose.runtime.remember {
                    androidx.compose.runtime.mutableStateOf(
                        com.cotixgo.app.data.security.AppLock.isEnabled(ctx),
                    )
                }
                SettingsScreen(
                    user = user,
                    appLockEnabled = appLockEnabled,
                    onToggleAppLock = { enabled ->
                        com.cotixgo.app.data.security.AppLock.setEnabled(ctx, enabled)
                        appLockEnabled = enabled
                    },
                    onCurrency = { vm.setMainCurrency(it) },
                    onServices = { nav.navigate(Routes.SERVICES) },
                    onRetentions = { nav.navigate(Routes.RETENTIONS) },
                    onProfile = { nav.navigate(Routes.PROFILE) },
                    onAccount = { nav.navigate(Routes.ACCOUNT) },
                    onBack = { nav.popBackStack() },
                )
            }
            composable(Routes.ACCOUNT) {
                val boundEmail by vm.accountEmail.collectAsStateWithLifecycle()
                val pending by vm.pendingOps.collectAsStateWithLifecycle()
                val pausedCount by vm.pausedOps.collectAsStateWithLifecycle()
                val baseUrl by vm.apiBaseUrl.collectAsStateWithLifecycle()
                val syncing by vm.syncing.collectAsStateWithLifecycle()
                val message by vm.lastSyncMessage.collectAsStateWithLifecycle()
                AccountScreen(
                    boundEmail = boundEmail,
                    pendingOps = pending,
                    pausedOps = pausedCount,
                    apiBaseUrl = baseUrl,
                    syncing = syncing,
                    lastMessage = message,
                    onRegister = { n, e, p -> vm.register(n, e, p) },
                    onLogin = { e, p -> vm.login(e, p) },
                    onRecover = { vm.recover(it) },
                    onSyncNow = { vm.syncNow() },
                    onSwitchAccount = { cp, ne, np, c -> vm.switchAccount(cp, ne, np, c) },
                    onSetBaseUrl = { vm.setBaseUrl(it) },
                    onConflicts = { nav.navigate(Routes.CONFLICTS) },
                    onBack = { nav.popBackStack() },
                )
            }
            composable(Routes.CONFLICTS) {
                val conflicts by vm.conflicts.collectAsStateWithLifecycle()
                val pausedList by vm.paused.collectAsStateWithLifecycle()
                val vcQuotes by vm.versionConflictQuotes.collectAsStateWithLifecycle()
                val vcReports by vm.versionConflictReports.collectAsStateWithLifecycle()
                ConflictsScreen(
                    conflicts = conflicts,
                    paused = pausedList,
                    versionConflictQuotes = vcQuotes.map { it.id to (it.officialNumber ?: it.provisionalNumber) },
                    versionConflictReports = vcReports.map { it.id to (it.officialNumber ?: it.provisionalNumber) },
                    detailOf = { op -> vm.pausedDetail(op) },
                    previewOf = { op -> vm.discardPreview(op) },
                    onKeepLocal = { vm.resolveKeepLocal(it) },
                    onKeepRemote = { vm.resolveKeepRemote(it) },
                    onRetry = { vm.retryPaused(it) },
                    onRestoreRemote = { opId, pending -> vm.restorePausedRemote(opId, pending) },
                    onAcknowledge = { vm.acknowledgeRejected(it) },
                    onDiscard = { opId, signature -> vm.discardPaused(opId, signature) },
                    onResolveVersions = { nav.navigate(Routes.resolveVersions(it)) },
                    onResolveReportVersions = { nav.navigate(Routes.resolveReportVersions(it)) },
                    onBack = { nav.popBackStack() },
                )
            }
            composable(
                Routes.RESOLVE_VERSIONS,
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) { entry ->
                val id = entry.arguments?.getString("id") ?: return@composable
                val versions by vm.observeQuoteVersions(id).collectAsStateWithLifecycle(emptyList())
                val quote by vm.observeQuote(id).collectAsStateWithLifecycle(null)
                ResolveVersionsScreen(
                    quoteNumber = quote?.officialNumber ?: quote?.provisionalNumber ?: "",
                    versions = versions,
                    onPick = { v -> vm.resolveQuoteVersions(id, v) { if (it) nav.popBackStack() } },
                    onBack = { nav.popBackStack() },
                )
            }
            composable(
                Routes.RESOLVE_REPORT_VERSIONS,
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) { entry ->
                val id = entry.arguments?.getString("id") ?: return@composable
                val versions by vm.observeReportVersions(id).collectAsStateWithLifecycle(emptyList())
                val report by vm.observeReport(id).collectAsStateWithLifecycle(null)
                ResolveReportVersionsScreen(
                    reportNumber = report?.officialNumber ?: report?.provisionalNumber ?: "",
                    versions = versions,
                    onPick = { v -> vm.resolveReportVersions(id, v) { if (it) nav.popBackStack() } },
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
