/*
 * SPDX-FileCopyrightText: 2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.basicsync.settings

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Build
import androidx.annotation.RequiresApi

abstract class WifiScanResultsWatcher {
    companion object {
        fun newInstance(context: Context): WifiScanResultsWatcher =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Api30Impl(context)
            } else {
                Api23Impl(context)
            }
    }

    protected var listener: Listener? = null

    open fun register(listener: Listener) {
        if (this.listener != null) {
            throw IllegalStateException("Listener already registered")
        }

        this.listener = listener
    }

    open fun unregister(listener: Listener) {
        if (this.listener !== listener) {
            throw IllegalStateException("Unregistering bad listener: $listener != ${this.listener}")
        }

        this.listener = null
    }

    interface Listener {
        fun onWifiScanResultsReady()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private class Api30Impl(private val context: Context) : WifiScanResultsWatcher() {
        private val wifiManager = context.getSystemService(WifiManager::class.java)

        private val callback = object : WifiManager.ScanResultsCallback() {
            override fun onScanResultsAvailable() {
                listener?.onWifiScanResultsReady()
            }
        }

        override fun register(listener: Listener) {
            super.register(listener)

            wifiManager.registerScanResultsCallback(context.mainExecutor, callback)
        }

        override fun unregister(listener: Listener) {
            super.unregister(listener)

            wifiManager.unregisterScanResultsCallback(callback)
        }
    }

    private class Api23Impl(private val context: Context) : WifiScanResultsWatcher() {
        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)) {
                    listener?.onWifiScanResultsReady()
                }
            }
        }

        override fun register(listener: Listener) {
            super.register(listener)

            context.registerReceiver(
                receiver,
                IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
            )
        }

        override fun unregister(listener: Listener) {
            super.unregister(listener)

            context.unregisterReceiver(receiver)
        }
    }
}
