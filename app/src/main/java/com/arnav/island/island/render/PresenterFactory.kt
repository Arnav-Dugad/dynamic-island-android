package com.arnav.island.island.render

import com.arnav.island.events.EventType
import com.arnav.island.island.render.presenters.BluetoothPresenter
import com.arnav.island.island.render.presenters.CalendarPresenter
import com.arnav.island.island.render.presenters.CallPresenter
import com.arnav.island.island.render.presenters.ChargingPresenter
import com.arnav.island.island.render.presenters.ConfirmPresenter
import com.arnav.island.island.render.presenters.CustomPresenter
import com.arnav.island.island.render.presenters.GlancePresenter
import com.arnav.island.island.render.presenters.LiveUpdatePresenter
import com.arnav.island.island.render.presenters.MediaPresenter
import com.arnav.island.island.render.presenters.MonitorPresenter
import com.arnav.island.island.render.presenters.NavigationPresenter
import com.arnav.island.island.render.presenters.NotificationPresenter
import com.arnav.island.island.render.presenters.ProgressPresenter
import com.arnav.island.island.render.presenters.ScreenshotPresenter
import com.arnav.island.island.render.presenters.StackPresenter
import com.arnav.island.island.render.presenters.SystemPresenter
import com.arnav.island.island.render.presenters.TimerPresenter
import com.arnav.island.island.render.presenters.TorchPresenter

/** Maps an event type to the renderer that draws its compact, bubble, toast and expanded forms. */
object PresenterFactory {
    fun create(type: EventType, rc: RenderContext): Presenter = when (type) {
        EventType.MEDIA -> MediaPresenter(rc)
        EventType.NOTIFICATION -> NotificationPresenter(rc)
        EventType.CHARGING, EventType.BATTERY_LOW, EventType.BATTERY_FULL -> ChargingPresenter(rc)
        EventType.BLUETOOTH -> BluetoothPresenter(rc)
        EventType.TIMER, EventType.TIMER_DONE, EventType.STOPWATCH -> TimerPresenter(rc)
        EventType.CALL_INCOMING, EventType.CALL_ONGOING -> CallPresenter(rc)
        EventType.NAVIGATION -> NavigationPresenter(rc)
        EventType.PROGRESS, EventType.PROGRESS_DONE -> ProgressPresenter(rc)
        EventType.SYSTEM, EventType.SCREEN_RECORD -> SystemPresenter(rc)
        EventType.MONITOR -> MonitorPresenter(rc)
        EventType.CUSTOM -> CustomPresenter(rc)
        EventType.GLANCE -> GlancePresenter(rc)
        EventType.STACK -> StackPresenter(rc)
        EventType.TORCH -> TorchPresenter(rc)
        EventType.CALENDAR -> CalendarPresenter(rc)
        EventType.LIVE_UPDATE -> LiveUpdatePresenter(rc)
        EventType.SCREENSHOT -> ScreenshotPresenter(rc)
        EventType.CONFIRM -> ConfirmPresenter(rc)
    }

    /** Presenters are reusable across event types that share a renderer class. */
    fun family(type: EventType): Int = when (type) {
        EventType.CHARGING, EventType.BATTERY_LOW, EventType.BATTERY_FULL -> 1
        EventType.TIMER, EventType.TIMER_DONE, EventType.STOPWATCH -> 2
        EventType.CALL_INCOMING, EventType.CALL_ONGOING -> 3
        EventType.PROGRESS, EventType.PROGRESS_DONE -> 4
        EventType.SYSTEM, EventType.SCREEN_RECORD -> 5
        else -> 100 + type.ordinal
    }
}
