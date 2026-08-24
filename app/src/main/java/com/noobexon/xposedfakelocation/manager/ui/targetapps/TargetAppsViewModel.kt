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
import android.util.Log

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

    val isCurrentlySelected = _uiState.value.selectedIdentifiers.contains(identifier)
    if (isCurrentlySelected) {
        // 取消选中：直接从 UI 和 Preferences 移除，然后调用 removeScope
        removeFromScope(service, item)
    } else {
        // 选中：先乐观更新 UI 和 Preferences，再请求 addScope
        // 立即更新 UI 状态
        _uiState.update { state ->
            state.copy(
                selectedIdentifiers = state.selectedIdentifiers + identifier,
                pendingIdentifiers = state.pendingIdentifiers + identifier
            ).recompute()
        }
        // 保存到 Preferences
        viewModelScope.launch {
            preferencesRepository.saveTargetApps(_uiState.value.selectedIdentifiers)
        }

        addToScope(service, item, identifier)
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

    private fun addToScope(service: XposedService, item: TargetAppItem, identifier: String) {
    val callback = object : XposedService.OnScopeEventListener {
        override fun onScopeRequestApproved(approved: List<String>) {
            viewModelScope.launch {
                // 请求成功，清除 pending 状态
                _uiState.update { state ->
                    state.copy(pendingIdentifiers = state.pendingIdentifiers - identifier)
                        .recompute()
                }
                // 重新从 Preferences 读取并同步（确保一致）
                refreshScope()
            }
        }

        override fun onScopeRequestFailed(message: String) {
            viewModelScope.launch {
                // 请求失败，回滚：从 selected 和 pending 中移除
                _uiState.update { state ->
                    state.copy(
                        selectedIdentifiers = state.selectedIdentifiers - identifier,
                        pendingIdentifiers = state.pendingIdentifiers - identifier
                    ).recompute()
                }
                // 同时从 Preferences 移除
                preferencesRepository.saveTargetApps(_uiState.value.selectedIdentifiers)
                _events.trySend(TargetAppsEvent.ScopeRequestFailed(message))
            }
        }
    }

    viewModelScope.launch {
        try {
            withContext(Dispatchers.IO) { service.requestScope(listOf(item.packageName), callback) }
        } catch (e: XposedService.ServiceException) {
            // 同步异常，回滚
            _uiState.update { state ->
                state.copy(
                    selectedIdentifiers = state.selectedIdentifiers - identifier,
                    pendingIdentifiers = state.pendingIdentifiers - identifier
                ).recompute()
            }
            preferencesRepository.saveTargetApps(_uiState.value.selectedIdentifiers)
            _events.trySend(TargetAppsEvent.ScopeRequestFailed(e.message ?: e.toString()))
        }
    }
}

    private fun removeFromScope(service: XposedService, item: TargetAppItem) {
    val identifier = item.identifier()
    // 立即从 UI 和 Preferences 移除
    _uiState.update { state ->
        state.copy(
            selectedIdentifiers = state.selectedIdentifiers - identifier,
            pendingIdentifiers = state.pendingIdentifiers + identifier
        ).recompute()
    }
    viewModelScope.launch {
        preferencesRepository.saveTargetApps(_uiState.value.selectedIdentifiers)
        try {
            withContext(Dispatchers.IO) { service.removeScope(listOf(item.packageName)) }
            // 移除成功，清除 pending
            _uiState.update { state ->
                state.copy(pendingIdentifiers = state.pendingIdentifiers - identifier)
                    .recompute()
            }
            refreshScope()
        } catch (e: XposedService.ServiceException) {
            // 移除失败，回滚，重新添加
            _uiState.update { state ->
                state.copy(
                    selectedIdentifiers = state.selectedIdentifiers + identifier,
                    pendingIdentifiers = state.pendingIdentifiers - identifier
                ).recompute()
            }
            preferencesRepository.saveTargetApps(_uiState.value.selectedIdentifiers)
            _events.trySend(TargetAppsEvent.ScopeRequestFailed(e.message ?: e.toString()))
        }
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
    // 获取所有已安装的应用（包括系统应用）
    val apps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
    val result = mutableListOf<TargetAppItem>()

    // 打印应用总数，便于调试
    Log.d("TargetAppsVM", "Total installed apps: ${apps.size}")

    for (info in apps) {
        // 过滤掉自身
        if (info.packageName == MANAGER_APP_PACKAGE_NAME) continue

        val label = info.loadLabel(packageManager).toString()
        val isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0

        // 对于所有应用（包括没有启动器的），都添加到列表
        // 用户可以根据需要搜索
        result.add(TargetAppItem(
            label = label,
            packageName = info.packageName,
            userId = 0,  // 所有应用都在当前用户下
            isSystemApp = isSystem
        ))

        // 打印每个应用的包名，以便调试
        Log.d("TargetAppsVM", "Added app: $label ($info.packageName)")
    }

    // 按标签排序
    result.distinctBy { it.packageName }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
}

private fun getInstalledApplicationsForUser(userId: Int): List<ApplicationInfo> {
    return try {
        val method = PackageManager::class.java.getDeclaredMethod(
            "getInstalledApplicationsAsUser",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType
        )
        @Suppress("UNCHECKED_CAST")
        method.invoke(packageManager, PackageManager.GET_META_DATA, userId) as List<ApplicationInfo>
    } catch (e: Exception) {
        Log.e("TargetAppsVM", "Failed to get apps for user $userId: ${e.message}")
        // 回退到当前用户
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
