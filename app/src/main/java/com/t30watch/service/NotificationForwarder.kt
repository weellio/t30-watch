package com.t30watch.service

import android.app.Notification
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.t30watch.ble.ConnectionState
import com.t30watch.ble.RawLog
import com.t30watch.ble.VeepooClient
import com.t30watch.ble.WatchMessage
import com.t30watch.ble.WatchSettingsClient
import com.t30watch.data.notify.NotificationLog
import com.t30watch.data.notify.BurstGate
import com.t30watch.data.notify.QuietHours
import com.t30watch.data.settings.AppSettings
import com.t30watch.di.AppScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Android calls this for every notification on the phone (once the user grants "Notification access").
 * Matching ones are queued and pushed to the watch one at a time.
 */
@AndroidEntryPoint
class NotificationForwarder : NotificationListenerService() {
    @Inject lateinit var settings: AppSettings
    @Inject lateinit var client: VeepooClient
    @Inject lateinit var watchSettings: WatchSettingsClient
    @Inject lateinit var notifyLog: NotificationLog
    @Inject lateinit var log: RawLog
    @Inject @AppScope lateinit var scope: CoroutineScope

    private data class Item(val key: String, val appName: String, val msg: WatchMessage)

    private val queue = Channel<Item>(capacity = 32)
    private val recentSent = LinkedHashMap<String, Long>()
    private val shownKeys = LinkedHashSet<String>()
    private val burst = BurstGate(60_000)
    private var activeCallKey: String? = null

    private var worker: kotlinx.coroutines.Job? = null

    override fun onListenerConnected() {
        log.app("notification listener connected")
        if (worker?.isActive == true) return
        worker = scope.launch {
            for (item in queue) {
                try {
                    watchSettings.loadNotifyCategoriesIfNeeded()
                    // The watch-side switch for this category must be on (same rule the watch applies).
                    var m = item.msg
                    // Categories this watch doesn't have at all (e.g. Telegram on the T30) go out as "Other".
                    if (m is WatchMessage.App && m.category != "other" && !watchSettings.categorySupported(m.category)) {
                        m = WatchMessage.App("other", listOf(item.appName, m.title).filter { it.isNotBlank() }.distinct().joinToString(" · "), m.text)
                    }
                    if (m is WatchMessage.App && !watchSettings.categoryOn(m.category)) continue
                    watchSettings.push(m)
                    notifyLog.sent(item.key)
                } catch (e: Exception) {
                    log.error("forward notification", e)
                    notifyLog.failed(item.key, e.message)
                }
            }
        }
    }

    override fun onListenerDisconnected() {
        log.app("notification listener disconnected")
    }

    override fun onDestroy() {
        worker?.cancel()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        if (pkg == packageName) return
        val n = sbn.notification ?: return
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        val appName = appLabel(pkg)
        notifyLog.seen(pkg, appName)
        // How loud the phone itself treats this one. Below DEFAULT = no sound or vibration on the phone.
        val importance = runCatching {
            val r = Ranking()
            if (currentRanking?.getRanking(sbn.key, r) == true) r.importance else null
        }.getOrNull()
        // An app updating a notification it already showed ("only alert once") isn't a new alert.
        val isRepeat = n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0 && synchronized(shownKeys) { sbn.key in shownKeys }
        synchronized(shownKeys) {
            shownKeys.add(sbn.key)
            if (shownKeys.size > 200) shownKeys.remove(shownKeys.first())
        }

        scope.launch {
            val s = settings.current()
            if (!s.forwardNotifications) return@launch
            if (pkg in s.mutedApps) return@launch
            if (client.state.value !is ConnectionState.Ready) return@launch
            if (currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL) return@launch // phone is in DND
            if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return@launch
            val isCall = n.category == Notification.CATEGORY_CALL
            if (s.quietHours && !(isCall && s.quietAllowCalls)) {
                val now = java.time.LocalTime.now()
                if (QuietHours.isQuiet(now.hour * 60 + now.minute, s.quietStartMin, s.quietEndMin)) {
                    log.app("quiet hours, not forwarded", pkg)
                    return@launch
                }
            }
            if (!isCall && s.skipSilent && importance != null && importance < NotificationManager.IMPORTANCE_DEFAULT) {
                log.app("silent on phone, not forwarded", pkg)
                return@launch
            }
            if (!isCall && isRepeat) return@launch
            if (!isCall && (n.flags and Notification.FLAG_ONGOING_EVENT != 0 || n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0)) return@launch
            if (title.isBlank() && text.isBlank()) return@launch

            val msg: WatchMessage = when {
                isCall -> {
                    if (activeCallKey == sbn.key) return@launch // same ringing call, updated
                    activeCallKey = sbn.key
                    WatchMessage.Call(name = title.ifBlank { null }, number = "")
                }
                pkg == Telephony.Sms.getDefaultSmsPackage(this@NotificationForwarder) -> WatchMessage.Sms(name = title.ifBlank { null }, number = "", text = text)
                else -> {
                    val cat = categoryFor(pkg)
                    val shownTitle = if (cat == "other") listOf(appName, title).filter { it.isNotBlank() }.distinct().joinToString(" · ") else title
                    WatchMessage.App(cat, shownTitle, text)
                }
            }
            // Apps re-post the same notification often; send each text once per 30 s.
            val dedupe = "$pkg|$title|$text"
            val now = System.currentTimeMillis()
            synchronized(recentSent) {
                recentSent.entries.removeIf { now - it.value > 30_000 }
                if (recentSent.containsKey(dedupe)) return@launch
                recentSent[dedupe] = now
            }
            if (!isCall && s.groupBursts) {
                when (val d = burst.offer(pkg, now)) {
                    BurstGate.Decision.Send -> Unit
                    BurstGate.Decision.Hold -> return@launch
                    is BurstGate.Decision.HoldAndFlushIn -> {
                        scope.launch {
                            kotlinx.coroutines.delay(d.delayMs)
                            val n = burst.drain(pkg, System.currentTimeMillis())
                            if (n == 0) return@launch
                            val s2 = settings.current()
                            val t = java.time.LocalTime.now()
                            if (!s2.forwardNotifications || pkg in s2.mutedApps) return@launch
                            if (s2.quietHours && QuietHours.isQuiet(t.hour * 60 + t.minute, s2.quietStartMin, s2.quietEndMin)) return@launch
                            log.app("forward summary", "$pkg +$n")
                            queue.trySend(Item(pkg, appName, WatchMessage.App(categoryFor(pkg), appName, "$n more notification" + if (n == 1) "" else "s")))
                        }
                        return@launch
                    }
                }
            }
            log.app("forward", "$pkg ${msg.javaClass.simpleName}")
            queue.trySend(Item(pkg, appName, msg))
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        synchronized(shownKeys) { shownKeys.remove(sbn.key) }
        if (sbn.key == activeCallKey) {
            activeCallKey = null
            scope.launch { runCatching { watchSettings.callEnded() } }
        }
    }

    private fun appLabel(pkg: String): String = runCatching {
        val pm = packageManager
        val info = if (android.os.Build.VERSION.SDK_INT >= 33) pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
        else @Suppress("DEPRECATION") pm.getApplicationInfo(pkg, 0)
        pm.getApplicationLabel(info).toString()
    }.getOrDefault(pkg)

    companion object {
        /** Phone app → watch category key (see WatchSettingsClient). Everything else → "other". */
        private val MAP = mapOf(
            "com.whatsapp" to "whats", "com.whatsapp.w4b" to "whats",
            "com.facebook.katana" to "facebook",
            "com.twitter.android" to "twitter",
            "com.linkedin.android" to "linkin",
            "jp.naver.line.android" to "line",
            "com.instagram.android" to "instagram",
            "com.snapchat.android" to "snapchat",
            "com.skype.raider" to "skype",
            "com.google.android.gm" to "gmail",
            "com.tencent.mm" to "wechat",
            "com.tencent.mobileqq" to "qq",
            "org.telegram.messenger" to "telegram",
            "com.facebook.orca" to "messenger",
        )

        fun categoryFor(pkg: String): String = MAP[pkg] ?: "other"
    }
}
