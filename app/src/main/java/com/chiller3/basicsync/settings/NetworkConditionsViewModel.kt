/*
 * SPDX-FileCopyrightText: 2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.basicsync.settings

import android.annotation.SuppressLint
import android.app.Application
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import com.chiller3.basicsync.Permissions
import com.chiller3.basicsync.Preferences
import com.chiller3.basicsync.syncthing.DeviceState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.max

class NetworkConditionsViewModel(application: Application) : AndroidViewModel(application),
    WifiScanResultsWatcher.Listener {
    companion object {
        private val TAG = NetworkConditionsViewModel::class.java.simpleName
    }

    private val wifiManager = application.getSystemService(WifiManager::class.java)
    private val prefs = Preferences(application)

    private val _allowed = MutableStateFlow(emptyList<String>())
    val allowed = _allowed.asStateFlow()

    private val _rawAvailable = MutableStateFlow(emptyList<String>())
    private val _available = MutableStateFlow(emptyList<String>())
    val available = _available.asStateFlow()

    private val scanResultsWatcher = WifiScanResultsWatcher.newInstance(application)

    init {
        refreshNetworks()

        scanResultsWatcher.register(this)

        // There is no initial callback sent.
        checkScanResults()
    }

    override fun onCleared() {
        scanResultsWatcher.unregister(this)
    }

    private fun refreshNetworks() {
        val newAllowed = prefs.allowedWifiNetworks.sorted()
        // Already sorted by signal strength.
        val newAvailable = _rawAvailable.value
            .asSequence()
            .mapNotNull { a ->
                // To avoid confusion, don't show a network in available networks if it is already
                // present in the allowed networks.
                if (newAllowed.none { DeviceState.ssidMatches(it, a) }) {
                    a
                } else {
                    null
                }
            }
            .toList()

        _allowed.update { newAllowed }
        _available.update { newAvailable }
    }

    private fun updateNetworks(networks: Set<String>) {
        prefs.allowedWifiNetworks = networks
        refreshNetworks()
    }

    fun addNetwork(newNetwork: String) {
        updateNetworks(LinkedHashSet(allowed.value).apply { add(newNetwork) })
    }

    fun removeNetwork(network: String) {
        val newNetworks = allowed.value
            .asSequence()
            .filter { it != network }
            .toSet()

        updateNetworks(newNetworks)
    }

    fun replaceNetwork(oldNetwork: String, newNetwork: String) {
        val newNetworks = allowed.value
            .asSequence()
            .map {
                if (it == oldNetwork) {
                    newNetwork
                } else {
                    it
                }
            }
            .toSet()

        updateNetworks(newNetworks)
    }

    fun checkScanResults() {
        if (!Permissions.have(getApplication(), Permissions.PRECISE_LOCATION)) {
            Log.d(TAG, "Precise location permissions not granted")
            return
        }

        @SuppressLint("MissingPermission")
        val results = wifiManager.scanResults
        Log.d(TAG, "Received ${results.size} scan result(s)")

        val networks = mutableMapOf<String, Int>()

        for (result in results) {
            val ssid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                result.wifiSsid?.toString()?.let(DeviceState::normalizeSsid)
            } else {
                // Older versions of Android don't support non-UTF-8 SSIDs at all.
                @Suppress("DEPRECATION")
                result.SSID
            }
            if (ssid == null) {
                continue
            }

            networks[ssid] = max(networks.getOrDefault(ssid, result.level), result.level)
        }

        Log.d(TAG, "${networks.size} unique SSIDs")

        _rawAvailable.update {
            networks
                .asSequence()
                .sortedByDescending { it.value }
                .map { it.key }
                .toList()
        }
        refreshNetworks()
    }

    override fun onWifiScanResultsReady() {
        checkScanResults()
    }
}
