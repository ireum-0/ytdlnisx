package com.ireum.ytdl.ui

import android.Manifest
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.os.bundleOf
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.findNavController
import androidx.navigation.fragment.findNavController
import com.ireum.ytdl.R
import com.ireum.ytdl.database.viewmodel.DownloadViewModel
import com.ireum.ytdl.util.ThemeUtil
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.elevation.SurfaceColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.system.exitProcess

open class BaseActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtil.updateTheme(this)
        window.navigationBarColor = SurfaceColors.SURFACE_2.getColor(this)
        super.onCreate(savedInstanceState)
    }

    fun askPermissions() {
        val permissions = arrayListOf<String>()
        val hasFilePerm = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            }
            else -> {
                (ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        == PackageManager.PERMISSION_GRANTED) &&
                        (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                                == PackageManager.PERMISSION_GRANTED)
            }
        }

        if (!hasFilePerm){
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                    permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                    permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
                else -> {
                    permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!checkNotificationPermission()){
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissions.isNotEmpty()){
            ActivityCompat.requestPermissions(
                this,
                permissions.toTypedArray(),
                1
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        for (i in permissions.indices) {
            if (permissions.contains(Manifest.permission.POST_NOTIFICATIONS)) continue
            if (grantResults[i] == PackageManager.PERMISSION_DENIED) {
                createPermissionRequestDialog()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun checkNotificationPermission(): Boolean {
        return (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED)
    }

    private fun createPermissionRequestDialog() {
        val dialog = MaterialAlertDialogBuilder(this)
        dialog.setTitle(getString(R.string.warning))
        dialog.setMessage(getString(R.string.request_permission_desc))
        dialog.setOnCancelListener { exit() }
        dialog.setNegativeButton(getString(R.string.exit_app)) { _: DialogInterface?, _: Int -> exit() }
        dialog.setPositiveButton(getString(R.string.ok)) { _: DialogInterface?, _: Int ->
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }
            startActivity(intent)
        }
        dialog.show()
    }

    private fun exit() {
        android.util.Log.e("ExitTrace", "System.exit requested", Throwable())
        finishAffinity()
        exitProcess(0)
    }

}
