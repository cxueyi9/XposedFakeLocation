package com.noobexon.xposedfakelocation.manager.ui.targetapps

import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserHandle
import android.os.UserManager
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.noobexon.xposedfakelocation.data.MANAGER_APP_PACKAGE_NAME
import com.noobexon.xposedfakelocation.data.repository.PreferencesRepository
import com.noobexon.xposedfakelocation.manager.App
import io.github.libxposed.service.XposedService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.reflect.Method

@Immutable
data class TargetAppItem(
    val label: String,
    val packageName: String,
    val userId: Int,
    val isSystemApp: Boolean = false,
    val isSelected: Boolean = false,
    val isPending: Boolean = false,
    val isRelaunching: Boolean = false
) {
    fun identifier(): String = "$packageName|$userId"
}

@Immutable
data class TargetAppsUiState(
    val apps: List<TargetAppItem> = emptyList(),
    val selectedIdentifiers: Set<String> = emptySet(),
    val pendingIdentifiers: Set<String> = emptySet(),
    val relaunchingIdentifiers: Set<String> = emptySet(),
    val filteredApps: List<TargetAppItem> = emptyList(),
    val searchQuery: String = "",
    val showUserApps: Boolean = true,
    val showSystemApps: Boolean = true,
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isModuleActive: Boolean = true
)

sealed interface TargetAppsEvent {
    data object ModuleNotActive : TargetAppsEvent
    data class ScopeRequestFailed(val message: String) : TargetAppsEvent
    data class Relaunched(val appLabel: String) : TargetAppsEvent
    data class RelaunchFailed(val appLabel: String) : TargetAppsEvent
    data object RootRequired : TargetAppsEvent
}

private fun List<TargetAppItem>.sortedBySelection(selected: Set<String>): List<TargetAppItem> =
    sortedWith(
        compareByDescending<TargetAppItem> { selected.contains(it.identifier()) }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.label }
    )

class TargetAppsViewModel(application: Application) : AndroidViewModel(application) {
    private val preferencesRepository = PreferencesRepository(application)
    private val packageManager = application.packageManager
    private val userManager = application.getSystemService(UserManager::class.java)

    private val _uiState = MutableStateFlow(TargetAppsUiState())
    val uiState: StateFlow<TargetAppsUiState> = _uiState.asStateFlow()

    private val _events = Channel<TargetAppsEvent>(Channel.BUFFERED)
    val events: Flow<TargetAppsEvent> = _events.receiveAsFlow()

    init {
        loadInstalledApps()
        observeService()
    }

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query).recompute() }
    }

    fun setShowUserApps(show: Boolean) {
        _uiState.update { it.copy(showUserApps = show).recompute() }
    }

    fun setShowSystemApps(show: Boolean) {
        _uiState.update { it.copy(showSystemApps = show).recompute() }
    }

    fun toggleApp(item: TargetAppItem) {
        val identifier = item.identifier()
        if (_uiState.value.pendingIdentifiers.contains(identifier)) return

        val service = App.service
        if (service == null) {
            _events.trySend(TargetAppsEvent.ModuleNotActive)
            return
        }

        if (_uiState.value.selectedIdentifiers.contains(identifier)) {
            removeFromScope(service, item)
        } else {
            addToScope(service, item)
        }
    }

    fun relaunchApp(item: TargetAppItem) {
        val identifier = item.identifier()
        if (_uiState.value.relaunchingIdentifiers.contains(identifier)) return
        val label = item.label

        setRelaunching(identifier, true)
        viewModelScope.launch {
            val hasRoot = withContext(Dispatchers.IO) { hasRootAccess() }
            if (!hasRoot) {
                setRelaunching(identifier, false)
                _events.trySend(TargetAppsEvent.RootRequired)
                return@launch
            }

            val killed = withContext(Dispatchers.IO) { runAsRoot("am force-stop ${item.packageName}") }
            if (!killed) {
                setRelaunching(identifier, false)
                _events.trySend(TargetAppsEvent.RelaunchFailed(label))
                return@launch
            }

            val launchIntent = packageManager.getLaunchIntentForPackage(item.packageName)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            setRelaunching(identifier, false)
            if (launchIntent != null) {
                getApplication<Application>().startActivity(launchIntent)
                _events.trySend(TargetAppsEvent.Relaunched(label))
            } else {
                _events.trySend(TargetAppsEvent.RelaunchFailed(label))
            }
        }
    }

    private fun hasRootAccess(): Boolean = try {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor() == 0 && output.contains("uid=0")
    } catch (e: Exception) {
        false
    }

    private fun runAsRoot(command: String): Boolean = try {
        Runtime.getRuntime().exec(arrayOf("su", "-c", command)).waitFor() == 0
    } catch (e: Exception) {
        false
    }

    private fun addToScope(service: XposedService, item: TargetAppItem) {
        val identifier = item.identifier()
        setPending(identifier, true)

        val callback = object : XposedService.OnScopeEventListener {
            override fun onScopeRequestApproved(approved: List<String>) {
                viewModelScope.launch {
                    setPending(identifier, false)
                    refreshScope()
                }
            }

            override fun onScopeRequestFailed(message: String) {
                viewModelScope.launch {
                    setPending(identifier, false)
                    _events.trySend(TargetAppsEvent.ScopeRequestFailed(message))
                }
            }
        }

        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { service.requestScope(listOf(item.packageName), callback) }
            } catch (e: XposedService.ServiceException) {
                setPending(identifier, false)
                _events.trySend(TargetAppsEvent.ScopeRequestFailed(e.message ?: e.toString()))
            }
        }
    }

    private fun removeFromScope(service: XposedService, item: TargetAppItem) {
        val identifier = item.identifier()
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { service.removeScope(listOf(item.packageName)) }
            } catch (e: XposedService.ServiceException) {
                _events.trySend(TargetAppsEvent.ScopeRequestFailed(e.message ?: e.toString()))
            }
            refreshScope()
        }
    }

    private fun observeService() {
        viewModelScope.launch {
            App.serviceState.collectLatest { service ->
                if (service == null) {
                    _uiState.update { state ->
                        state.copy(
                            isModuleActive = false,
                            selectedIdentifiers = emptySet(),
                            pendingIdentifiers = emptySet(),
                            relaunchingIdentifiers = emptySet()
                        ).recompute()
                    }
                } else {
                    _uiState.update { it.copy(isModuleActive = true) }
                    refreshScope(sortApps = true)
                }
            }
        }
    }

    private suspend fun refreshScope(sortApps: Boolean = false) {
        val storedIdentifiers = preferencesRepository.getTargetApps()
        _uiState.update { state ->
            val nextApps = if (sortApps && state.apps.isNotEmpty()) state.apps.sortedBySelection(storedIdentifiers)
                           else state.apps
            state.copy(selectedIdentifiers = storedIdentifiers, apps = nextApps).recompute()
        }
    }

    private fun setPending(identifier: String, pending: Boolean) {
        _uiState.update { state ->
            val nextPending = state.pendingIdentifiers.toMutableSet().apply {
                if (pending) add(identifier) else remove(identifier)
            }
            state.copy(pendingIdentifiers = nextPending).recompute()
        }
    }

    private fun setRelaunching(identifier: String, relaunching: Boolean) {
        _uiState.update { state ->
            val nextRelaunching = state.relaunchingIdentifiers.toMutableSet().apply {
                if (relaunching) add(identifier) else remove(identifier)
            }
            state.copy(relaunchingIdentifiers = nextRelaunching).recompute()
        }
    }

    private fun TargetAppsUiState.recompute(): TargetAppsUiState {
        val query = searchQuery.trim()
        val typeFiltered = apps.filter { if (it.isSystemApp) showSystemApps else showUserApps }
        val source = if (query.isEmpty()) typeFiltered else typeFiltered.filter {
            it.label.contains(query, ignoreCase = true) ||
                it.packageName.contains(query, ignoreCase = true)
        }
        return copy(
            filteredApps = source.map { app ->
                app.copy(
                    isSelected = selectedIdentifiers.contains(app.identifier()),
                    isPending = pendingIdentifiers.contains(app.identifier()),
                    isRelaunching = relaunchingIdentifiers.contains(app.identifier())
                )
            }
        )
    }

private suspend fun fetchInstalledApps(): List<TargetAppItem> = withContext(Dispatchers.IO) {
    // 获取所有用户
    val users = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        @Suppress("DEPRECATION")
        userManager.users ?: emptyList()
    } else {
        emptyList()
    }

    val result = mutableListOf<TargetAppItem>()
    for (user in users) {
        val userId = user.id
        val apps = getInstalledApplicationsForUser(userId)
        for (info in apps) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setPackage(info.packageName)
            val resolveInfo = packageManager.resolveActivity(intent, 0)
            if (resolveInfo != null && info.packageName != MANAGER_APP_PACKAGE_NAME) {
                val label = info.loadLabel(packageManager).toString()
                val isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                result.add(TargetAppItem(
                    label = label,
                    packageName = info.packageName,
                    userId = userId,
                    isSystemApp = isSystem
                ))
            }
        }
    }
    result.distinctBy { "${it.packageName}|${it.userId}" }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
}

    private fun getInstalledApplicationsForUser(userId: Int): List<ApplicationInfo> {
        return try {
            val method: Method = PackageManager::class.java.getDeclaredMethod(
                "getInstalledApplicationsAsUser",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            @Suppress("UNCHECKED_CAST")
            method.invoke(packageManager, PackageManager.GET_META_DATA, userId) as List<ApplicationInfo>
        } catch (e: Exception) {
            packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
        }
    }

    private fun loadInstalledApps() {
        viewModelScope.launch {
            val fetched = fetchInstalledApps()
            _uiState.update { state ->
                state.copy(apps = fetched.sortedBySelection(state.selectedIdentifiers), isLoading = false).recompute()
            }
        }
    }

    fun refresh() {
        if (_uiState.value.isRefreshing) return
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            try {
                _uiState.update { state -> state.copy(apps = fetchInstalledApps()).recompute() }
                refreshScope(sortApps = true)
            } finally {
                _uiState.update { it.copy(isRefreshing = false) }
            }
        }
    }

    suspend fun saveSelectedIdentifiers(identifiers: Set<String>) {
        preferencesRepository.saveTargetApps(identifiers)
    }
}
