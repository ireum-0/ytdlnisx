package com.ireum.ytdl

import android.app.ActionBar.LayoutParams
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.forEach
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.FragmentContainerView
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.fragment.findNavController
import androidx.navigation.ui.setupWithNavController
import androidx.preference.PreferenceManager
import com.anggrayudi.storage.file.getAbsolutePath
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.repository.DownloadRepository
import com.ireum.ytdl.database.viewmodel.CookieViewModel
import com.ireum.ytdl.database.viewmodel.DownloadViewModel
import com.ireum.ytdl.database.viewmodel.ResultViewModel
import com.ireum.ytdl.database.viewmodel.SettingsViewModel
import com.ireum.ytdl.ui.BaseActivity
import com.ireum.ytdl.ui.HomeFragment
import com.ireum.ytdl.ui.downloads.DownloadQueueMainFragment
import com.ireum.ytdl.ui.downloads.HistoryFragment
import com.ireum.ytdl.ui.more.settings.SettingsActivity
import com.ireum.ytdl.util.CrashListener
import com.ireum.ytdl.util.NavbarUtil
import com.ireum.ytdl.util.NavbarUtil.applyNavBarStyle
import com.ireum.ytdl.util.ThemeUtil
import com.ireum.ytdl.util.UiUtil
import com.ireum.ytdl.util.UpdateUtil
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.elevation.SurfaceColors
import com.google.android.material.navigation.NavigationBarView
import com.google.android.material.navigation.NavigationView
import com.google.android.material.navigationrail.NavigationRailView
import com.google.android.material.snackbar.Snackbar
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.Reader
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import kotlin.system.exitProcess


class MainActivity : BaseActivity() {

    lateinit var context: Context
    private lateinit var preferences: SharedPreferences
    private lateinit var resultViewModel: ResultViewModel
    private lateinit var cookieViewModel: CookieViewModel
    private lateinit var downloadViewModel: DownloadViewModel
    private lateinit var settingsViewModel: SettingsViewModel
    private var navigationView: NavigationView? = null
    private var navigationBarView: NavigationBarView? = null
    private lateinit var navHostFragment : NavHostFragment
    private lateinit var navController : NavController
    private var skipNavStateRestoreForLaunchIntent: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashListener(this).registerExceptionHandler()
        ThemeUtil.updateTheme(this)
        window.navigationBarColor = SurfaceColors.SURFACE_2.getColor(this)
        setContentView(R.layout.activity_main)
        context = baseContext
        resultViewModel = ViewModelProvider(this)[ResultViewModel::class.java]
        cookieViewModel = ViewModelProvider(this)[CookieViewModel::class.java]
        downloadViewModel = ViewModelProvider(this)[DownloadViewModel::class.java]
        settingsViewModel = ViewModelProvider(this)[SettingsViewModel::class.java]
        preferences = PreferenceManager.getDefaultSharedPreferences(context)

        if (preferences.getBoolean("incognito", false)) {
            lifecycleScope.launch(Dispatchers.IO){
                resultViewModel.deleteAll()
            }
        }



        askPermissions()
        checkUpdate()

        navHostFragment = supportFragmentManager.findFragmentById(R.id.frame_layout) as NavHostFragment
        navController = navHostFragment.findNavController()
        skipNavStateRestoreForLaunchIntent = shouldSkipNavStateRestore(intent)
        kotlin.runCatching {
            navigationView = findViewById(R.id.navigationView)
        }
        kotlin.runCatching {
            navigationBarView = findViewById(R.id.bottomNavigationView)
        }

        navigationBarView?.apply {
            window.decorView.setOnApplyWindowInsetsListener { view: View, windowInsets: WindowInsets? ->
                if (windowInsets == null) {
                    return@setOnApplyWindowInsetsListener view.onApplyWindowInsets(view.rootWindowInsets)
                }
                val windowInsetsCompat = WindowInsetsCompat.toWindowInsetsCompat(
                    windowInsets, view
                )
                val isImeVisible = windowInsetsCompat.isVisible(WindowInsetsCompat.Type.ime())
                visibility = if (isImeVisible) View.GONE else View.VISIBLE
                view.onApplyWindowInsets(windowInsets)
            }
        }

        NavbarUtil.init(this)

        navigationBarView?.apply {
            if (savedInstanceState == null){
                val graph = navController.navInflater.inflate(R.navigation.nav_graph)
                graph.setStartDestination(NavbarUtil.getStartFragmentId(this@MainActivity))
                navController.graph = graph
            }
            applyNavBarStyle()

            val showingDownloadQueue = NavbarUtil.getNavBarItems(this@MainActivity).any { n -> n.itemId == R.id.downloadQueueMainFragment && n.isVisible }

            setupWithNavController(navController)
            setOnItemReselectedListener {
                when (it.itemId) {
                    R.id.homeFragment -> {
                        kotlin.runCatching {
                            (navHostFragment.childFragmentManager.primaryNavigationFragment as? HomeFragment)?.scrollToTop()
                        }
                    }
                    R.id.historyFragment -> {
                        if(!showingDownloadQueue) {
                            navController.navigate(R.id.downloadQueueMainFragment)
                        }else{
                            kotlin.runCatching {
                                (navHostFragment.childFragmentManager.primaryNavigationFragment as? HistoryFragment)?.scrollToTop()
                            }
                        }
                    }
                    R.id.downloadQueueMainFragment -> {
                        kotlin.runCatching {
                            (navHostFragment.childFragmentManager.primaryNavigationFragment as? DownloadQueueMainFragment)?.scrollToActive()
                        }
                    }
                    R.id.moreFragment -> {
                        val intent = Intent(context, SettingsActivity::class.java)
                        startActivity(intent)
                    }
                }
            }

            val activeDownloadsBadge = if (showingDownloadQueue) {
                getOrCreateBadge(R.id.downloadQueueMainFragment)
            }else{
                getOrCreateBadge(R.id.historyFragment)
            }
            lifecycleScope.launch {
                downloadViewModel.activePausedDownloadsCount.collectLatest {
                    if (it == 0) {
                        activeDownloadsBadge.isVisible = false
                        activeDownloadsBadge.clearNumber()
                    }
                    else {
                        activeDownloadsBadge.isVisible = true
                        activeDownloadsBadge.number = it
                    }
                }
            }

            val showingNavbarItems = NavbarUtil.getNavBarItems(this@MainActivity).filter { it.isVisible }.map { it.itemId }
            navController.addOnDestinationChangedListener { _, destination, _ ->
                Handler(Looper.getMainLooper()).post {
                    if (showingNavbarItems.contains(destination.id)) {
                        showBottomNavigation()
                    }else{
                        hideBottomNavigation()
                    }
                }

            }

            visibilityChanged {
                if (it.isVisible){
                    val curr = navController.currentDestination?.id
                    if (!showingNavbarItems.contains(curr)) hideBottomNavigation()
                }
            }
        }

        navigationView?.apply {
            setupWithNavController(navController)
            //terminate button
            menu.getItem(8).setOnMenuItemClickListener {
                if (preferences.getBoolean("ask_terminate_app", true)){
                    var doNotShowAgain = false
                    val terminateDialog = MaterialAlertDialogBuilder(this@MainActivity)
                    terminateDialog.setTitle(getString(R.string.confirm_delete_history))
                    val dialogView = layoutInflater.inflate(R.layout.dialog_terminate_app, null)
                    val checkbox = dialogView.findViewById<CheckBox>(R.id.doNotShowAgain)
                    terminateDialog.setView(dialogView)
                    checkbox.setOnCheckedChangeListener { compoundButton, _ ->
                        doNotShowAgain = compoundButton.isChecked
                    }

                    terminateDialog.setNegativeButton(getString(R.string.cancel)) { dialogInterface: DialogInterface, _: Int -> dialogInterface.cancel() }
                    terminateDialog.setPositiveButton(getString(R.string.ok)) { _: DialogInterface?, _: Int ->
                        lifecycleScope.launch {
                            withContext(Dispatchers.IO) {
                                val activeDownloads = downloadViewModel.getActiveAndPostProcessingDownloads().toMutableList()
                                activeDownloads.map { it.status = DownloadRepository.Status.Queued.toString() }
                                activeDownloads.forEach { downloadViewModel.updateDownload(it) }
                            }
                            if (doNotShowAgain){
                                preferences.edit().putBoolean("ask_terminate_app", false).apply()
                            }
                            finishAndRemoveTask()
                            finishAffinity()
                            android.util.Log.e("ExitTrace", "exitProcess requested (terminate dialog)", Throwable())
                            exitProcess(0)
                        }
                    }
                    terminateDialog.show()
                }else{
                    finishAndRemoveTask()
                    android.util.Log.e("ExitTrace", "exitProcess requested (terminate no dialog)", Throwable())
                    exitProcess(0)
                }
                true
            }
            //settings button
            menu.getItem(9).setOnMenuItemClickListener {
                val intent = Intent(context, SettingsActivity::class.java)
                startActivity(intent)
                true
            }

            getHeaderView(0).findViewById<TextView>(R.id.title).text = ThemeUtil.getStyledAppName(this@MainActivity)
        }

        cookieViewModel.updateCookiesFile()
        val intent = intent
        handleIntents(intent)

        if (preferences.getBoolean("auto_update_ytdlp", false)){
            CoroutineScope(SupervisorJob()).launch(Dispatchers.IO) {
                kotlin.runCatching {
                    if(DBManager.getInstance(this@MainActivity).downloadDao.getDownloadsCountByStatus(listOf("Active", "Queued")) == 0){
                        if (UpdateUtil(this@MainActivity).updateYoutubeDL().status == UpdateUtil.YTDLPUpdateStatus.DONE) {
                            val version = YoutubeDL.getInstance().version(context)
                            val snack = Snackbar.make(findViewById(R.id.frame_layout),
                                this@MainActivity.getString(R.string.ytld_update_success) + " [${version}]",
                                Snackbar.LENGTH_LONG)

                            navigationBarView?.apply {
                                snack.setAnchorView(this)
                            }
                            snack.show()
                        }
                    }
                }

            }
        }
    }
    override fun onSaveInstanceState(savedInstanceState: Bundle) {
        super.onSaveInstanceState(savedInstanceState)
        savedInstanceState.putBundle("nav_state", navController.saveState())
    }
    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        if (skipNavStateRestoreForLaunchIntent) {
            return
        }
        val state = savedInstanceState.getBundle("nav_state")
        if (state != null) {
            navController.restoreState(state)
        }
    }

    private fun View.visibilityChanged(action: (View) -> Unit) {
        this.viewTreeObserver.addOnGlobalLayoutListener {
            val newVis: Int = this.visibility
            if (this.tag as Int? != newVis) {
                this.tag = this.visibility
                // visibility has changed
                action(this)
            }
        }
    }


    fun hideBottomNavigation(){
        navigationBarView?.apply {
            if (this is BottomNavigationView){
                this@MainActivity.findViewById<FragmentContainerView>(R.id.frame_layout).updateLayoutParams<ConstraintLayout.LayoutParams> {
                    bottomToTop = ConstraintLayout.LayoutParams.UNSET
                    bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                }
                this.animate()?.translationY(this.height.toFloat())?.setDuration(300)?.withEndAction {
                    this.visibility = View.GONE
                }?.start()
            }else if (this is NavigationRailView){
                this@MainActivity.findViewById<FragmentContainerView>(R.id.frame_layout).updateLayoutParams {
                    this.width = LayoutParams.MATCH_PARENT
                }

                if (resources.getBoolean(R.bool.is_right_to_left)){
                    this.animate()?.translationX(this.width.toFloat())?.setDuration(300)?.withEndAction {
                        this.visibility = View.GONE
                    }?.start()
                }else{
                    this.animate()?.translationX(-this.width.toFloat())?.setDuration(300)?.withEndAction {
                        this.visibility = View.GONE
                    }?.start()
                }
            }
        }


    }

    fun showBottomNavigation(){
        navigationBarView?.apply {
            if (this is BottomNavigationView){
                this@MainActivity.findViewById<FragmentContainerView>(R.id.frame_layout).updateLayoutParams<ConstraintLayout.LayoutParams> {
                    bottomToTop = R.id.bottomNavigationView
                    bottomToBottom = ConstraintLayout.LayoutParams.UNSET
                }
                this.animate()?.translationY(0F)?.setDuration(300)?.withEndAction {
                    this.visibility = View.VISIBLE
                }?.start()
            }else if (this is NavigationRailView){
                this@MainActivity.findViewById<FragmentContainerView>(R.id.frame_layout).updateLayoutParams {
                    this.width = 0
                }
                this.animate()?.translationX(0F)?.setDuration(300)?.withEndAction {
                    this.visibility = View.VISIBLE
                }?.start()
            }
        }

    }

    fun disableBottomNavigation(){
        navigationBarView?.menu?.forEach { it.isEnabled = false }
        navigationView?.menu?.forEach { it.isEnabled = false }
    }

    fun enableBottomNavigation(){
        navigationBarView?.menu?.forEach { it.isEnabled = true }
        navigationView?.menu?.forEach { it.isEnabled = true }
    }

    override fun onResume() {
        super.onResume()
        //incognito header
        val incognitoHeader = findViewById<TextView>(R.id.incognito_header)
        if (preferences.getBoolean("incognito", false)){
            incognitoHeader.visibility = View.VISIBLE
            window.statusBarColor = (incognitoHeader.background as ColorDrawable).color
        }else{
            window.statusBarColor = getColor(android.R.color.transparent)
            incognitoHeader.visibility = View.GONE
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        logHistoryReturn("onNewIntent intent=${describeIntent(intent)} taskId=$taskId isTaskRoot=$isTaskRoot")
        handleIntents(intent)
    }

    private fun shouldSkipNavStateRestore(intent: Intent?): Boolean {
        val safeIntent = intent ?: return false
        if (safeIntent.action != Intent.ACTION_VIEW) return false
        return !safeIntent.getStringExtra("destination").isNullOrBlank()
    }

    private fun handleIntents(intent: Intent) {
        logHistoryReturn("handleIntents intent=${describeIntent(intent)} taskId=$taskId isTaskRoot=$isTaskRoot")
        val action = intent.action
        val type = intent.type
        if (Intent.ACTION_SEND == action && type != null) {
            Log.e(TAG, action)
            try {
                val uri = if (Build.VERSION.SDK_INT >= 33){
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                }else{
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }

                var downloadType = DownloadType.valueOf(preferences.getString("preferred_download_type", "video") ?: "video")
                if (preferences.getBoolean("quick_download", false) || downloadType == DownloadType.command) {
                    val safeUri = uri ?: return
                    val docFile = DocumentFile.fromSingleUri(this, safeUri)
                    if (docFile?.exists() == true){
                        val bundle = Bundle()
                        val path = docFile.getAbsolutePath(this)
                        if (downloadType == DownloadType.auto) {
                            downloadType = downloadViewModel.getDownloadType(null, path)
                        }

                        bundle.putParcelable("result", downloadViewModel.createEmptyResultItem(path))
                        bundle.putSerializable("type", downloadType)
                        navController.navigate(R.id.downloadBottomSheetDialog, bundle)
                        return
                    }
                }

                val safeUri = uri ?: return
                val input = contentResolver.openInputStream(safeUri) ?: throw IOException("Could not open shared stream")
                val textBuilder = StringBuilder()
                val reader: Reader = BufferedReader(
                    InputStreamReader(
                        input, Charset.forName(
                            StandardCharsets.UTF_8.name()
                        )
                    )
                )
                reader.use {
                    val buffer = CharArray(4096)
                    var totalChars = 0
                    while (true) {
                        val read = it.read(buffer)
                        if (read == -1) break
                        totalChars += read
                        if (totalChars > MAX_SHARED_TEXT_CHARS) {
                            throw IOException("Shared text file is too large")
                        }
                        textBuilder.append(buffer, 0, read)
                    }
                }
                val bundle = Bundle()
                bundle.putString("url", textBuilder.toString())
                navController.popBackStack(R.id.homeFragment, true)
                navController.navigate(
                    R.id.homeFragment,
                    bundle
                )
            } catch (e: Exception) {
                Toast.makeText(context, "Couldn't read file", Toast.LENGTH_LONG).show()
                e.printStackTrace()
            }
        }else if (action == Intent.ACTION_VIEW){

            val navbarItems = NavbarUtil.getNavBarItems(this)
            when(intent.getStringExtra("destination")){
                "Downloads" -> {
                    val localSessionId = intent.getStringExtra("localAddSessionId")
                    if (!localSessionId.isNullOrBlank()) {
                        com.ireum.ytdl.util.LocalAddStorage.setOpenSession(this, localSessionId)
                    }
                    val bundle = buildHistoryRestoreBundle(intent)
                    val currentHistoryFragment =
                        navHostFragment.childFragmentManager.primaryNavigationFragment as? HistoryFragment
                    if (navController.currentDestination?.id == R.id.historyFragment && currentHistoryFragment != null) {
                        currentHistoryFragment.restoreFromReturnBundle(bundle)
                        if (!localSessionId.isNullOrBlank()) {
                            currentHistoryFragment.openLocalAddSessionFromIntent(localSessionId)
                        }
                    } else {
                        navController.navigate(R.id.historyFragment, bundle)
                    }
                }
                "Queue" -> {
                    if (navbarItems.any { n -> n.itemId == R.id.downloadQueueMainFragment && n.isVisible }) {
                        navController.popBackStack(navController.graph.startDestinationId, true)
                    }

                    val bundle = Bundle()
                    intent.getStringExtra("tab")?.apply {
                        bundle.putString("tab", this)
                    }
                    intent.getLongExtra("reconfigure", 0L).apply {
                        if (this != 0L){
                            bundle.putLong("reconfigure", this)
                        }
                    }
                    navController.navigate(R.id.downloadQueueMainFragment, bundle)
                }
                "Search" -> {
                    val bundle = Bundle()
                    bundle.putBoolean("search", true)
                    navController.popBackStack(R.id.homeFragment, true)
                    navController.navigate(
                        R.id.homeFragment,
                        bundle
                    )
                }
            }
        }
    }


    private fun checkUpdate() {
        if (preferences.getBoolean("update_app", false)) {
            val updateUtil = UpdateUtil(this)
            CoroutineScope(Dispatchers.IO).launch {
                val res = updateUtil.tryGetNewVersion()
                if (res.isSuccess) {
                    if (preferences.getBoolean("automatic_backup", false)) {
                        settingsViewModel.backup()
                    }
                    withContext(Dispatchers.Main) {
                        val update = res.getOrNull() ?: return@withContext
                        UiUtil.showNewAppUpdateDialog(update, this@MainActivity, preferences)
                    }
                }

            }
        }
    }

    private fun buildHistoryRestoreBundle(intent: Intent): Bundle {
        return Bundle().apply {
            intent.getBundleExtra(HistoryFragment.EXTRA_RESTORE_SCREEN_SNAPSHOT)?.let { snapshot ->
                putBundle(HistoryFragment.EXTRA_RESTORE_SCREEN_SNAPSHOT, Bundle(snapshot))
            }
            if (intent.hasExtra(HistoryFragment.EXTRA_RESTORE_SCROLL_POSITION)) {
                putInt(
                    HistoryFragment.EXTRA_RESTORE_SCROLL_POSITION,
                    intent.getIntExtra(
                        HistoryFragment.EXTRA_RESTORE_SCROLL_POSITION,
                        -1
                    )
                )
                putInt(
                    HistoryFragment.EXTRA_RESTORE_SCROLL_OFFSET,
                    intent.getIntExtra(
                        HistoryFragment.EXTRA_RESTORE_SCROLL_OFFSET,
                        0
                    )
                )
                val restoreItemId = intent.getLongExtra(
                    HistoryFragment.EXTRA_RESTORE_SCROLL_ITEM_ID,
                    -1L
                )
                if (restoreItemId > 0L) {
                    putLong(
                        HistoryFragment.EXTRA_RESTORE_SCROLL_ITEM_ID,
                        restoreItemId
                    )
                }
                if (intent.hasExtra(HistoryFragment.EXTRA_RESTORE_SCROLL_ITEM_TOP)) {
                    putInt(
                        HistoryFragment.EXTRA_RESTORE_SCROLL_ITEM_TOP,
                        intent.getIntExtra(
                            HistoryFragment.EXTRA_RESTORE_SCROLL_ITEM_TOP,
                            0
                        )
                    )
                }
            }
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val MAX_SHARED_TEXT_CHARS = 128 * 1024
    }

    private fun logHistoryReturn(event: String) {
        return
    }

    private fun describeIntent(intent: Intent?): String {
        val safeIntent = intent ?: return "null"
        val extras = buildList {
            if (safeIntent.hasExtra("destination")) {
                add("destination=${safeIntent.getStringExtra("destination")}")
            }
            if (safeIntent.hasExtra(com.ireum.ytdl.ui.downloads.HistoryFragment.EXTRA_RESTORE_SCROLL_POSITION)) {
                add(
                    "restore=" +
                        safeIntent.getIntExtra(com.ireum.ytdl.ui.downloads.HistoryFragment.EXTRA_RESTORE_SCROLL_POSITION, -1) +
                        "/" +
                        safeIntent.getIntExtra(com.ireum.ytdl.ui.downloads.HistoryFragment.EXTRA_RESTORE_SCROLL_OFFSET, 0)
                )
            }
        }.joinToString(",")
        return "action=${safeIntent.action} flags=0x${safeIntent.flags.toString(16)} extras=[$extras]"
    }
}

