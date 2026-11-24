package app.stockavg

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings

data class AppInfo(val label: String, val pkg: String)

object Importer {
    fun serviceEnabled(c: Context): Boolean {
        val cn = ComponentName(c, ReaderService::class.java)
        val s = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return s.split(':').any {
            it.equals(cn.flattenToString(), true) || it.equals(cn.flattenToShortString(), true)
        }
    }

    fun launcherApps(c: Context): List<AppInfo> {
        val pm = c.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(i, 0)
            .map { AppInfo(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .distinctBy { it.pkg }
            .sortedBy { it.label }
    }

    /** 已記住的、或自動找到名稱含「投資先生」的 App。 */
    fun resolvePkg(c: Context): String? {
        val saved = Store.targetPkg(c)
        if (saved.isNotEmpty() && c.packageManager.getLaunchIntentForPackage(saved) != null) return saved
        return launcherApps(c).firstOrNull { it.label.contains("投資先生") }?.pkg
    }

    fun candidates(c: Context): List<AppInfo> =
        launcherApps(c).filter {
            it.label.contains("元大") || it.label.contains("投資") || it.label.contains("yuanta", true)
        }

    fun begin(c: Context, pkg: String): Boolean {
        val li = c.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        Store.setTarget(c, pkg)
        Store.setImporting(c, true)
        ImportBus.state.value = ImportState.Waiting
        ReaderService.instance?.beginImport()
        c.startActivity(li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    fun cancel(c: Context) {
        Store.setImporting(c, false)
        ImportBus.state.value = ImportState.Idle
        ReaderService.instance?.endImport()
    }
}
