/*
 * SPDX-FileCopyrightText: 2024-2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.basicsync.settings

import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.setFragmentResultListener
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.SwitchPreferenceCompat
import androidx.preference.get
import androidx.preference.size
import com.chiller3.basicsync.Permissions
import com.chiller3.basicsync.PreferenceBaseFragment
import com.chiller3.basicsync.Preferences
import com.chiller3.basicsync.R
import com.chiller3.basicsync.dialog.WifiNetworkDialogFragment
import com.chiller3.basicsync.syncthing.SyncthingService
import com.chiller3.basicsync.view.SplitSwitchPreference
import kotlinx.coroutines.launch

class NetworkConditionsFragment : PreferenceBaseFragment(), Preference.OnPreferenceClickListener,
    Preference.OnPreferenceChangeListener {
    companion object {
        private const val PREF_ALLOWED_PREFIX = "${Preferences.CATEGORY_ALLOWED_WIFI_NETWORKS}_"
        private const val PREF_AVAILABLE_PREFIX = "${Preferences.CATEGORY_AVAILABLE_WIFI_NETWORKS}_"
    }

    private val viewModel: NetworkConditionsViewModel by viewModels()

    private lateinit var prefs: Preferences
    private lateinit var categoryPermissions: PreferenceCategory
    private lateinit var categoryAllowedWifiNetworks: PreferenceCategory
    private lateinit var categoryAvailableWifiNetworks: PreferenceCategory
    private lateinit var prefNetworkAllowWifi: SwitchPreferenceCompat
    private lateinit var prefAllowPreciseLocation: Preference
    private lateinit var prefAllowBackgroundLocation: Preference
    private lateinit var prefAddNewNetwork: Preference

    private val requestPermissionRequired =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            if (granted.all { it.value }) {
                // We could be starting from a state where allowed Wi-Fi networks were previously
                // configured, but the location permission was later disabled. When re-enabling the
                // permission, the service needs to request the foreground location permission.
                SyncthingService.start(requireContext(), SyncthingService.ACTION_RENOTIFY)

                // Android will not notify us that we now have access to the Wi-Fi scan results.
                viewModel.checkScanResults()

                refreshWifi(null)
            } else {
                startActivity(Permissions.getAppInfoIntent(requireContext()))
            }
        }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences_network, rootKey)

        val context = requireContext()

        prefs = Preferences(context)

        categoryPermissions = findPreference(Preferences.CATEGORY_PERMISSIONS)!!
        categoryAllowedWifiNetworks = findPreference(Preferences.CATEGORY_ALLOWED_WIFI_NETWORKS)!!
        categoryAvailableWifiNetworks = findPreference(Preferences.CATEGORY_AVAILABLE_WIFI_NETWORKS)!!

        prefNetworkAllowWifi = findPreference(Preferences.PREF_NETWORK_ALLOW_WIFI)!!
        prefNetworkAllowWifi.onPreferenceChangeListener = this

        prefAllowPreciseLocation = findPreference(Preferences.PREF_ALLOW_PRECISE_LOCATION)!!
        prefAllowPreciseLocation.onPreferenceClickListener = this

        prefAllowBackgroundLocation = findPreference(Preferences.PREF_ALLOW_BACKGROUND_LOCATION)!!
        prefAllowBackgroundLocation.onPreferenceClickListener = this

        prefAddNewNetwork = findPreference(Preferences.PREF_ADD_NEW_NETWORK)!!
        prefAddNewNetwork.onPreferenceClickListener = this

        refreshWifi(null)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                viewModel.allowed.collect { networks ->
                    updateDynamicPrefs(
                        categoryAllowedWifiNetworks,
                        PREF_ALLOWED_PREFIX,
                        // The user is allowed to manually edit the name.
                        splitPref = true,
                        checked = true,
                        networks,
                    )
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                viewModel.available.collect { networks ->
                    updateDynamicPrefs(
                        categoryAvailableWifiNetworks,
                        PREF_AVAILABLE_PREFIX,
                        splitPref = false,
                        checked = false,
                        networks,
                    )
                }
            }
        }

        setFragmentResultListener(WifiNetworkDialogFragment.TAG) { _, result ->
            if (result.getBoolean(WifiNetworkDialogFragment.RESULT_SUCCESS)) {
                val oldNetwork = result.getString(WifiNetworkDialogFragment.RESULT_OLD_NAME)
                val network = result.getString(WifiNetworkDialogFragment.RESULT_NAME)!!

                if (oldNetwork != null) {
                    viewModel.replaceNetwork(oldNetwork, network)
                } else {
                    viewModel.addNetwork(network)
                }
            }
        }
    }

    private fun refreshWifi(newValue: Boolean?) {
        val context = requireContext()
        val allowWifi = newValue ?: prefs.networkAllowWifi

        val allowPreciseLocation = Permissions.have(context, Permissions.PRECISE_LOCATION)
        prefAllowPreciseLocation.isVisible = !allowPreciseLocation

        val allowBackgroundLocation = Permissions.have(context, Permissions.BACKGROUND_LOCATION)
        prefAllowBackgroundLocation.isVisible = !allowBackgroundLocation
        // This permission cannot be granted until precise location is granted.
        prefAllowBackgroundLocation.isEnabled = allowPreciseLocation

        val allowLocation = allowPreciseLocation && allowBackgroundLocation

        categoryPermissions.isVisible = !allowLocation
        categoryPermissions.isEnabled = allowWifi
        categoryAllowedWifiNetworks.isEnabled = allowWifi && allowLocation
        categoryAvailableWifiNetworks.isEnabled = allowWifi && allowLocation
    }

    private fun updateDynamicPrefs(
        category: PreferenceCategory,
        keyPrefix: String,
        splitPref: Boolean,
        checked: Boolean,
        networks: List<String>,
    ) {
        val context = requireContext()

        for (i in (0 until category.size).reversed()) {
            val p = category[i]

            if (p.key.startsWith(keyPrefix)) {
                category.removePreference(p)
            }
        }

        for (network in networks) {
            val p = if (splitPref) {
                SplitSwitchPreference(context).apply {
                    onPreferenceClickListener = this@NetworkConditionsFragment
                }
            } else {
                SwitchPreferenceCompat(context)
            }.apply {
                key = keyPrefix + network
                isPersistent = false
                title = network
                isIconSpaceReserved = false
                isChecked = checked
                onPreferenceChangeListener = this@NetworkConditionsFragment
            }

            category.addPreference(p)
        }
    }

    override fun onPreferenceClick(preference: Preference): Boolean {
        when {
            preference == prefAllowPreciseLocation -> {
                requestPermissionRequired.launch(Permissions.PRECISE_LOCATION)
                return true
            }
            preference == prefAllowBackgroundLocation -> {
                requestPermissionRequired.launch(Permissions.BACKGROUND_LOCATION)
                return true
            }
            preference == prefAddNewNetwork -> {
                WifiNetworkDialogFragment.newInstance(requireContext(), null)
                    .show(parentFragmentManager.beginTransaction(), WifiNetworkDialogFragment.TAG)
                return true
            }
            preference.key.startsWith(PREF_ALLOWED_PREFIX) -> {
                val network = preference.key.substring(PREF_ALLOWED_PREFIX.length)

                WifiNetworkDialogFragment.newInstance(requireContext(), network)
                    .show(parentFragmentManager.beginTransaction(), WifiNetworkDialogFragment.TAG)

                return true
            }
        }

        return false
    }

    override fun onPreferenceChange(preference: Preference, newValue: Any?): Boolean {
        when {
            preference == prefNetworkAllowWifi -> {
                refreshWifi(newValue as Boolean)
                return true
            }
            preference.key.startsWith(PREF_ALLOWED_PREFIX) -> if (newValue == false) {
                val network = preference.key.substring(PREF_ALLOWED_PREFIX.length)
                viewModel.removeNetwork(network)
                return true
            }
            preference.key.startsWith(PREF_AVAILABLE_PREFIX) -> if (newValue == true) {
                val network = preference.key.substring(PREF_AVAILABLE_PREFIX.length)
                viewModel.addNetwork(network)
                return true
            }
        }

        return false
    }
}
