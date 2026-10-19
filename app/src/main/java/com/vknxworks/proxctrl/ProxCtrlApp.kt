package com.vknxworks.proxctrl

import android.app.Application
import com.vknxworks.proxctrl.data.notify.Notifier
import com.vknxworks.proxctrl.data.notify.NotifyWorker
import com.vknxworks.proxctrl.data.notify.Push
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

class ProxCtrlApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifier.createChannels(this)
        val c = container
        c.appScope.launch {
            // Keep the background check and push registration in line with the settings.
            val first = c.notifySettings.current()
            NotifyWorker.schedule(this@ProxCtrlApp, first)
            Push.load(this@ProxCtrlApp, first.push)
            c.notifySettings.settings.drop(1).distinctUntilChanged().collect { s ->
                NotifyWorker.schedule(this@ProxCtrlApp, s)
                if (!s.push && Push.status.value !is com.vknxworks.proxctrl.data.notify.PushStatus.Off) Push.unregister(this@ProxCtrlApp)
            }
        }
    }
}
