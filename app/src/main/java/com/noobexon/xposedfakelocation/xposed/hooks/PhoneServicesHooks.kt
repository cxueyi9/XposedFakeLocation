package com.noobexon.xposedfakelocation.xposed.hooks

import android.os.Binder
import android.os.UserHandle
import android.telephony.CellInfo
import android.telephony.NeighboringCellInfo
import android.util.Log
import com.noobexon.xposedfakelocation.xposed.utils.PreferencesUtil
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Hooker
import java.lang.reflect.Method

class PhoneServicesHooks(
    private val module: XposedInterface,
    private val classLoader: ClassLoader
) {
    private val tag = "[PhoneServicesHooks]"

    fun init() {
        val phoneInterfaceManagerClass = findClass(
            classLoader,
            "com.android.phone.PhoneInterfaceManager"
        ) ?: return

        hookCellLocation(phoneInterfaceManagerClass)
        hookCellInfo(phoneInterfaceManagerClass)
        module.log(Log.INFO, tag, "Instantiated hooks successfully")
    }

    private fun hookCellLocation(phoneInterfaceManagerClass: Class<*>) {
        hookAll(phoneInterfaceManagerClass, "getCellLocation") { chain ->
            val result = chain.proceed()
            if (shouldSpoofArgs(chain.args)) {
                module.log(Log.INFO, tag, "Cleared cell location while spoofing.")
                null
            } else {
                result
            }
        }
    }

    private fun hookCellInfo(phoneInterfaceManagerClass: Class<*>) {
        hookAll(phoneInterfaceManagerClass, "getAllCellInfo") { chain ->
            if (shouldSpoofArgs(chain.args)) {
                module.log(Log.INFO, tag, "Cleared all cell info while spoofing.")
                emptyList<CellInfo>()
            } else {
                chain.proceed()
            }
        }

        hookAll(phoneInterfaceManagerClass, "getNeighboringCellInfo") { chain ->
            if (shouldSpoofArgs(chain.args)) {
                module.log(Log.INFO, tag, "Cleared neighboring cell info while spoofing.")
                emptyList<NeighboringCellInfo>()
            } else {
                chain.proceed()
            }
        }

        hookAll(phoneInterfaceManagerClass, "requestCellInfoUpdateInternal") { chain ->
            if (shouldSpoofArgs(chain.args)) {
                module.log(Log.INFO, tag, "Blocked async cell info update while spoofing.")
                defaultReturnValue(chain.executable as? Method)
            } else {
                chain.proceed()
            }
        }
    }

    private fun hookAll(clazz: Class<*>, methodName: String, hooker: Hooker) {
        val methods = clazz.declaredMethods.filter { it.name == methodName }
        if (methods.isEmpty()) {
            module.log(Log.WARN, tag, "No method named $methodName on ${clazz.name}")
            return
        }

        var hooked = 0
        methods.forEach { method ->
            try {
                module.hook(method).intercept(hooker)
                hooked++
            } catch (e: Throwable) {
                module.log(Log.ERROR, tag, "Failed hooking ${clazz.name}#$methodName: ${e.message}")
            }
        }

        if (hooked > 0) {
            module.log(Log.INFO, tag, "Hooked ${clazz.name}#$methodName ($hooked overloads).")
        }
    }

    private fun findClass(classLoader: ClassLoader, vararg names: String): Class<*>? {
        names.forEach { name ->
            try {
                return Class.forName(name, false, classLoader)
            } catch (_: Throwable) {
                // Keep trying ROM-specific framework names.
            }
        }
        module.log(Log.WARN, tag, "None of these classes were found: ${names.joinToString()}")
        return null
    }

    private fun shouldSpoofArgs(args: List<Any?>?): Boolean {
        if (PreferencesUtil.getIsPlaying() != true) return false
        val uid = Binder.getCallingUid()
        val userId = Binder.getCallingUid() / 100000
        return args?.asSequence()
            ?.mapNotNull(::extractPackageName)
            ?.any { PreferencesUtil.isPackageTargeted(it, userId) } == true
    }

    private fun extractPackageName(value: Any?): String? {
        if (value is String) return value.takeIf { "." in it && !it.startsWith("android.") }
        return null
    }

    private fun defaultReturnValue(method: Method?): Any? {
        return when (method?.returnType) {
            java.lang.Boolean.TYPE -> false
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0F
            java.lang.Double.TYPE -> 0.0
            else -> null
        }
    }
}
