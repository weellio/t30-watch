package com.t30watch

import android.app.Application
import com.t30watch.demo.DemoSeeder
import com.t30watch.di.AppScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import com.t30watch.data.sync.SyncEngine
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application entry point. Hilt builds its dependency graph from here.
 * [SyncEngine] is injected so it exists from process start: it listens for "watch ready" events and runs
 * the auto-sync timer even when no screen is open.
 */
@HiltAndroidApp
class T30App : Application() {
    @Inject lateinit var sync: SyncEngine
    @Inject lateinit var demo: DemoSeeder
    @Inject @AppScope lateinit var scope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEMO) scope.launch { demo.seedIfEmpty() }
    }
}
