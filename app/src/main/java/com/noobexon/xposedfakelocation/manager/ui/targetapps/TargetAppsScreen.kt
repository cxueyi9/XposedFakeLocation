package com.noobexon.xposedfakelocation.manager.ui.targetapps

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.noobexon.xposedfakelocation.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun TargetAppsScreen(
    navController: NavController,
    viewModel: TargetAppsViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, viewModel) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.events.collect { event ->
                val message = when (event) {
                    is TargetAppsEvent.ModuleNotActive ->
                        context.getString(R.string.target_apps_module_inactive)
                    is TargetAppsEvent.ScopeRequestFailed ->
                        context.getString(R.string.target_apps_scope_request_failed, event.message)
                    is TargetAppsEvent.Relaunched ->
                        context.getString(R.string.target_apps_relaunching, event.appLabel)
                    is TargetAppsEvent.RelaunchFailed ->
                        context.getString(R.string.target_apps_relaunch_failed, event.appLabel)
                    is TargetAppsEvent.RootRequired ->
                        context.getString(R.string.target_apps_root_required)
                }
                snackbarHostState.showSnackbar(message)
            }
        }
    }

    TargetAppsContent(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onNavigateUp = { navController.navigateUp() },
        onSearchQueryChange = viewModel::updateSearchQuery,
        onToggle = viewModel::toggleApp,
        onRelaunch = viewModel::relaunchApp,
        onRefresh = viewModel::refresh,
        onSetShowUserApps = viewModel::setShowUserApps,
        onSetShowSystemApps = viewModel::setShowSystemApps,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TargetAppsContent(
    uiState: TargetAppsUiState,
    snackbarHostState: SnackbarHostState,
    onNavigateUp: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onToggle: (TargetAppItem) -> Unit,
    onRelaunch: (TargetAppItem) -> Unit,
    onRefresh: () -> Unit,
    onSetShowUserApps: (Boolean) -> Unit,
    onSetShowSystemApps: (Boolean) -> Unit,
) {
    var searchActive by remember { mutableStateOf(false) }
    var filterExpanded by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    val pullRefreshState = rememberPullToRefreshState()
    LaunchedEffect(pullRefreshState.isRefreshing) {
        if (pullRefreshState.isRefreshing) onRefresh()
    }
    val listState = rememberLazyListState()
    LaunchedEffect(uiState.isRefreshing) {
        if (!uiState.isRefreshing && pullRefreshState.isRefreshing) {
            pullRefreshState.endRefresh()
            listState.scrollToItem(0)
        }
    }

    BackHandler(enabled = searchActive) {
        searchActive = false
        onSearchQueryChange("")
        focusManager.clearFocus()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState, modifier = Modifier.imePadding()) },
        topBar = {
            TopAppBar(
                title = {
                    if (searchActive) {
                        val focusRequester = remember { FocusRequester() }
                        LaunchedEffect(Unit) { focusRequester.requestFocus() }
                        val searchFieldDescription = stringResource(R.string.cd_search_apps)
                        TextField(
                            value = uiState.searchQuery,
                            onValueChange = onSearchQueryChange,
                            placeholder = { Text(stringResource(R.string.target_apps_search_label)) },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                disabledIndicatorColor = Color.Transparent,
                                focusedTextColor = MaterialTheme.colorScheme.onPrimary,
                                unfocusedTextColor = MaterialTheme.colorScheme.onPrimary,
                                cursorColor = MaterialTheme.colorScheme.onPrimary,
                                focusedPlaceholderColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f),
                                unfocusedPlaceholderColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester)
                                .semantics { contentDescription = searchFieldDescription }
                        )
                    } else {
                        Text(stringResource(R.string.screen_target_apps))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                ),
                navigationIcon = {
                    IconButton(onClick = {
                        if (searchActive) {
                            searchActive = false
                            onSearchQueryChange("")
                            focusManager.clearFocus()
                        } else {
                            onNavigateUp()
                        }
                    }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(
                                if (searchActive) R.string.cd_collapse_search else R.string.cd_navigate_back
                            )
                        )
                    }
                },
                actions = {
                    if (searchActive) {
                        IconButton(onClick = {
                            if (uiState.searchQuery.isEmpty()) {
                                searchActive = false
                                focusManager.clearFocus()
                            } else {
                                onSearchQueryChange("")
                            }
                        }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cd_clear_search))
                        }
                    } else {
                        IconButton(onClick = { searchActive = true }) {
                            Icon(Icons.Default.Search, contentDescription = stringResource(R.string.cd_search_apps))
                        }
                        Box {
                            IconButton(onClick = { filterExpanded = true }) {
                                Icon(Icons.Default.FilterList, contentDescription = stringResource(R.string.cd_filter_apps))
                            }
                            DropdownMenu(
                                expanded = filterExpanded,
                                onDismissRequest = { filterExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.target_apps_filter_user)) },
                                    onClick = { onSetShowUserApps(!uiState.showUserApps) },
                                    leadingIcon = {
                                        Checkbox(
                                            checked = uiState.showUserApps,
                                            onCheckedChange = null
                                        )
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.target_apps_filter_system)) },
                                    onClick = { onSetShowSystemApps(!uiState.showSystemApps) },
                                    leadingIcon = {
                                        Checkbox(
                                            checked = uiState.showSystemApps,
                                            onCheckedChange = null
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) {
                    focusManager.clearFocus()
                    if (searchActive) {
                        searchActive = false
                        onSearchQueryChange("")
                    }
                }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
            ) {
                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = stringResource(R.string.target_apps_selected_count, uiState.selectedIdentifiers.size),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )

                if (!uiState.isModuleActive) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.target_apps_module_inactive),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                when {
                    uiState.isLoading -> {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                    uiState.filteredApps.isEmpty() && uiState.searchQuery.isNotBlank() -> {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = stringResource(R.string.target_apps_no_results, uiState.searchQuery),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    uiState.filteredApps.isEmpty() && (!uiState.showUserApps || !uiState.showSystemApps) -> {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = stringResource(R.string.target_apps_filter_no_results),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    else -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clipToBounds()
                                .nestedScroll(pullRefreshState.nestedScrollConnection)
                        ) {
                            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                                items(uiState.filteredApps, key = { "${it.packageName}|${it.userId}" }) { app ->
                                    TargetAppRow(
                                        app = app,
                                        onToggle = { onToggle(app) },
                                        onRelaunch = { onRelaunch(app) }
                                    )
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                }
                            }
                            PullToRefreshContainer(
                                state = pullRefreshState,
                                modifier = Modifier.align(Alignment.TopCenter)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TargetAppRow(
    app: TargetAppItem,
    onToggle: () -> Unit,
    onRelaunch: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !app.isPending, onClick = onToggle)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIcon(
                packageName = app.packageName,
                label = app.label
            )
            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${app.packageName} [${app.userId}]",  // 显示用户ID
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (app.isSelected) {
                if (app.isRelaunching) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    IconButton(onClick = onRelaunch) {
                        Icon(
                            Icons.Default.RestartAlt,
                            contentDescription = stringResource(R.string.target_apps_relaunch_cd, app.label)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(4.dp))
            }

            if (app.isPending) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp
                )
            } else {
                Checkbox(
                    checked = app.isSelected,
                    onCheckedChange = { onToggle() }
                )
            }
        }
    }
}

@Composable
private fun AppIcon(
    packageName: String,
    label: String
) {
    val context = LocalContext.current
    val iconBitmap by produceState<Bitmap?>(initialValue = null, packageName) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.packageManager.getApplicationIcon(packageName).toBitmap()
            }.getOrNull()
        }
    }

    val bitmap = iconBitmap
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = stringResource(R.string.cd_app_icon, label),
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
        )
    } else {
        Surface(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = label.firstOrNull()?.uppercase() ?: "?",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}