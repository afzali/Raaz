package io.raaz.messenger.util

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ProcessLifecycleOwner

object SessionLockManager : DefaultLifecycleObserver {

    val isLocked = MutableLiveData(true)

    private var timeoutMs: Long = 5 * 60 * 1000L
    private var lastActiveMs: Long = 0L
    private var backgroundedAt: Long = 0L

    fun init(timeoutMs: Long) {
        this.timeoutMs = timeoutMs
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    fun unlock() {
        lastActiveMs = System.currentTimeMillis()
        isLocked.postValue(false)
    }

    fun lock() {
        isLocked.postValue(true)
    }

    fun updateTimeout(ms: Long) {
        timeoutMs = ms
    }

    fun recordActivity() {
        lastActiveMs = System.currentTimeMillis()
    }

    override fun onStop(owner: LifecycleOwner) {
        backgroundedAt = System.currentTimeMillis()
    }

    override fun onStart(owner: LifecycleOwner) {
        if (timeoutMs <= 0) return
        val elapsed = System.currentTimeMillis() - backgroundedAt
        if (backgroundedAt > 0 && elapsed > timeoutMs) {
            lock()
        }
    }
}
