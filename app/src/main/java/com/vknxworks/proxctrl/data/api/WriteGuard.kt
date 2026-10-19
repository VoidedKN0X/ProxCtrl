package com.vknxworks.proxctrl.data.api

/**
 * Asked before every request that could change something on a server (anything but GET), after the
 * read-only check. The app lock sets [check] to ask for the fingerprint or screen lock when "Before
 * changes" is on; it returns false when the user cancels, and the request fails with
 * [PveError.Cancelled] without being sent. By default everything is allowed.
 */
object WriteGuard {
    @Volatile
    var check: suspend () -> Boolean = { true }
}
