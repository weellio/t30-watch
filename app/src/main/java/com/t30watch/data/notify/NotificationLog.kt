package com.t30watch.data.notify

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/** Apps seen posting notifications (for the mute list) and simple forwarding stats. In memory only. */
@Singleton
class NotificationLog @Inject constructor() {
    data class AppRow(val pkg: String, val label: String, val lastSeen: Long, val sent: Int, val lastError: String?)

    private val _apps = MutableStateFlow<Map<String, AppRow>>(emptyMap())
    val apps: StateFlow<Map<String, AppRow>> = _apps.asStateFlow()

    fun seen(pkg: String, label: String) = _apps.update { m ->
        val old = m[pkg]
        m + (pkg to (old?.copy(label = label, lastSeen = System.currentTimeMillis()) ?: AppRow(pkg, label, System.currentTimeMillis(), 0, null)))
    }

    fun sent(pkg: String) = _apps.update { m -> m[pkg]?.let { m + (pkg to it.copy(sent = it.sent + 1, lastError = null)) } ?: m }

    fun failed(pkg: String, error: String?) = _apps.update { m -> m[pkg]?.let { m + (pkg to it.copy(lastError = error)) } ?: m }
}
