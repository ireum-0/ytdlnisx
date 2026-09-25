package com.ireum.ytdl.ui.more.settings

import android.app.Activity
import android.widget.Toast
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.lifecycle.lifecycleScope
import androidx.activity.result.contract.ActivityResultContracts
import androidx.navigation.fragment.findNavController
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import androidx.preference.SwitchPreferenceCompat
import com.ireum.ytdl.R
import com.ireum.ytdl.database.RestoreGate
import com.ireum.ytdl.database.RestoreMutationAdmission
import com.ireum.ytdl.util.UiUtil
import com.ireum.ytdl.util.storage.ConfiguredDownloadArchiveStore
import com.ireum.ytdl.work.AlarmScheduler
import com.ireum.ytdl.work.CleanupScheduleCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar


class DownloadSettingsFragment : BaseSettingsFragment() {
    override val title: Int = R.string.downloads

    private lateinit var archivePath: Preference
    private var cleanupTransitionController: CleanupSchedulePreferenceController? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.downloading_preferences, rootKey)
        val preferences = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val rememberDownloadType = findPreference<SwitchPreferenceCompat>("remember_download_type")
        val downloadType = findPreference<ListPreference>("preferred_download_type")
        downloadType?.isEnabled = rememberDownloadType?.isChecked == false
        rememberDownloadType?.setOnPreferenceClickListener {
            downloadType?.isEnabled = !rememberDownloadType.isChecked
            true
        }

        val preventDuplicateDownloads = findPreference<ListPreference>("prevent_duplicate_downloads")
        preventDuplicateDownloads?.setOnPreferenceChangeListener { _, newValue ->
            archivePath.isVisible = newValue == "download_archive"
            true
        }

        archivePath = findPreference("download_archive_path")!!
        // Display only. The persisted preference stays the original provider
        // URI; a formatted summary must never become storage authority.
        archivePath.summary = ConfiguredDownloadArchiveStore.describe(requireContext())
        archivePath.isVisible = preferences.getString("prevent_duplicate_downloads", "") == "download_archive"
        archivePath.onPreferenceClickListener =
            Preference.OnPreferenceClickListener {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                archivePathResultLauncher.launch(intent)
                true
            }

        val cleanupLeftoverDownloads = findPreference<ListPreference>("cleanup_leftover_downloads")
        cleanupLeftoverDownloads?.let { cleanupPreference ->
            cleanupPreference.value =
                CleanupScheduleCoordinator.currentCadenceForSettings(requireContext())
            val transitionController = CleanupSchedulePreferenceController(
                context = requireContext(),
                scope = lifecycleScope,
                applyPersistedCadence = { persistedCadence ->
                    cleanupPreference.value = persistedCadence
                },
            )
            cleanupTransitionController = transitionController
            cleanupPreference.setOnPreferenceChangeListener { _, newValue ->
                transitionController.request(newValue)
            }
        }


        val scheduler = AlarmScheduler(requireContext())

        val useAlarmManagerInsteadOfWorkManager = findPreference<SwitchPreferenceCompat>("use_alarm_for_scheduling")
        useAlarmManagerInsteadOfWorkManager?.setOnPreferenceChangeListener { preference, newValue ->
            var allowChange = true
            if (newValue as Boolean){
                if (!scheduler.canSchedule() && Build.VERSION.SDK_INT >= 31){
                    Intent().also { intent ->
                        intent.action = Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM
                        requireContext().startActivity(intent)
                    }
                    allowChange = false
                }
            }

            allowChange
        }

        val useScheduler = findPreference<SwitchPreferenceCompat>("use_scheduler")
        val scheduleStart = findPreference<Preference>("schedule_start")
        scheduleStart?.summary = preferences.getString("schedule_start", "00:00")
        val scheduleEnd = findPreference<Preference>("schedule_end")
        scheduleEnd?.summary = preferences.getString("schedule_end", "05:00")

        useScheduler?.setOnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as Boolean
            if (enabled && !scheduler.canSchedule() && Build.VERSION.SDK_INT >= 31) {
                Intent().also { intent ->
                    intent.action = Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM
                    requireContext().startActivity(intent)
                }
                return@setOnPreferenceChangeListener false
            }
            val changed = scheduler.updateSchedulerEnabled(enabled)
            if (changed) useScheduler?.isChecked = enabled
            // The actual durable write already occurred inside the shared
            // authority transition; prevent AndroidX from persisting again
            // after the listener returns.
            false
        }
        scheduleStart?.setOnPreferenceClickListener {
            UiUtil.showTimePicker(parentFragmentManager, preferences) {
                val hr = it.get(Calendar.HOUR_OF_DAY)
                val mn = it.get(Calendar.MINUTE)
                val formattedTime = String.format("%02d", hr) + ":" + String.format("%02d", mn)
                if (scheduler.updateScheduleBoundary("schedule_start", formattedTime)) {
                    scheduleStart.summary = formattedTime
                }
            }
            true
        }

        scheduleEnd?.setOnPreferenceClickListener {
            UiUtil.showTimePicker(parentFragmentManager, preferences) {
                val hr = it.get(Calendar.HOUR_OF_DAY)
                val mn = it.get(Calendar.MINUTE)
                val formattedTime = String.format("%02d", hr) + ":" + String.format("%02d", mn)
                if (scheduler.updateScheduleBoundary("schedule_end", formattedTime)) {
                    scheduleEnd.summary = formattedTime
                }
            }
            true
        }

        findPreference<EditTextPreference>("proxy")?.apply {
            val s = getString(R.string.socks5_proxy_summary)
            summary = if (text.isNullOrBlank()) {
                s
            }else {
                "${s}\n[${text}]"
            }
            setOnPreferenceChangeListener { _, newValue ->
                summary = if ((newValue as String?).isNullOrBlank()) {
                    s
                }else {
                    "${s}\n[${newValue}]"
                }
                true
            }
        }

        findPreference<ListPreference>("preferred_download_type")?.apply {
            val s = getString(R.string.preferred_download_type_summary)
            summary = if (value.isNullOrBlank()) {
                s
            }else {
                "${s}\n[${entries[entryValues.indexOf(value)]}]"
            }
            setOnPreferenceChangeListener { _, newValue ->
                summary = if ((newValue as String?).isNullOrBlank()) {
                    s
                }else {
                    "${s}\n[${entries[entryValues.indexOf(newValue)]}]"
                }
                true
            }
        }

        findPreference<EditTextPreference>("limit_rate")?.apply {
            val s = getString(R.string.limit_rate_summary)
            summary = if (text.isNullOrBlank()) {
                s
            }else {
                "${s}\n[${text}]"
            }
            setOnPreferenceChangeListener { _, newValue ->
                summary = if ((newValue as String?).isNullOrBlank()) {
                    s
                }else {
                    "${s}\n[${newValue}]"
                }
                true
            }
        }

        findPreference<EditTextPreference>("buffer_size")?.apply {
            val s = getString(R.string.buffer_size_summary)
            summary = if (text.isNullOrBlank()) {
                s
            }else {
                "${s}\n[${text}]"
            }
            setOnPreferenceChangeListener { _, newValue ->
                summary = if ((newValue as String?).isNullOrBlank()) {
                    s
                }else {
                    "${s}\n[${newValue}]"
                }
                true
            }
        }

        findPreference<EditTextPreference>("socket_timeout")?.apply {
            val s = getString(R.string.socket_timeout_description)
            summary = if (text.isNullOrBlank()) {
                s
            }else {
                "${s}\n[${text}]"
            }
            setOnPreferenceChangeListener { _, newValue ->
                summary = if ((newValue as String?).isNullOrBlank()) {
                    s
                }else {
                    "${s}\n[${newValue}]"
                }
                true
            }
        }

        findPreference<Preference>("reset_preferences")?.setOnPreferenceClickListener {
            if (RestoreGate.isRestoreInProgress(requireContext())) return@setOnPreferenceClickListener false
            UiUtil.showGenericConfirmDialog(requireContext(), getString(R.string.reset), getString(R.string.reset_preferences_in_screen)) {
                cleanupTransitionController?.cancelPendingRequest()
                resetDownloadingPreferences(preferences)
            }
            true
        }
    }

    /**
     * Resets this screen without allowing the generic preference reset to
     * bypass the cleanup scheduler's authority transition.  The coordinator
     * is deliberately awaited from lifecycle-owned background work: it may
     * wait for an admitted destructive cleanup effect, but the preference
     * callback and Android main thread never wait synchronously.
     */
    private fun resetDownloadingPreferences(
        preferences: android.content.SharedPreferences,
    ) {
        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val cleanupDisabled = runCatching {
                withContext(Dispatchers.IO) {
                    CleanupScheduleCoordinator.configure(appContext, null)
                }
            }.getOrDefault(false)
            if (!cleanupDisabled) {
                if (isAdded) {
                    findPreference<ListPreference>("cleanup_leftover_downloads")?.value =
                        CleanupScheduleCoordinator.currentCadenceForSettings(appContext)
                }
                return@launch
            }
            if (!isAdded) {
                return@launch
            }

            // Keep the coordinator-owned disabled value in place while the
            // other downloading preferences are reset.  Removing this key
            // after the coordinated transition would make the generic reset
            // another writer of cleanup authority.
            resetPreferences(
                editor = preferences.edit(),
                key = R.xml.downloading_preferences,
                excludedKeys = setOf("cleanup_leftover_downloads"),
            )
            if (!isAdded) return@launch
            requireActivity().recreate()
            val fragmentId = findNavController().currentDestination?.id
            if (fragmentId != null) {
                findNavController().popBackStack(fragmentId, true)
                findNavController().navigate(fragmentId)
            }
        }
    }

    private var archivePathResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let {
                activity?.contentResolver?.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }

            val path = result.data!!.data.toString()
            val preferences = PreferenceManager.getDefaultSharedPreferences(requireContext())
            val editor = preferences.edit()
            // Persist the provider URI itself. A reconstructed filesystem
            // pathname would silently drop the SAF authority.
            editor.putString(ConfiguredDownloadArchiveStore.PREFERENCE_KEY, path)
            RestoreMutationAdmission.applyOrdinaryPreferences(requireContext(), editor)
            archivePath.summary = ConfiguredDownloadArchiveStore.describe(requireContext())
        }
    }

}
