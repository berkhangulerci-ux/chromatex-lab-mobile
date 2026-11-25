package org.chromatex.lab

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import org.chromatex.lab.data.Catalog
import org.chromatex.lab.data.DeviceId
import org.chromatex.lab.data.SessionPrefs
import org.chromatex.lab.data.UpdateClient
import org.chromatex.lab.data.UploadClient
import org.chromatex.lab.databinding.ActivityMainBinding
import org.chromatex.lab.ui.CaptureFragment
import org.chromatex.lab.ui.GalleryFragment
import org.chromatex.lab.ui.SettingsFragment
import org.chromatex.lab.ui.StatsFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) Toast.makeText(this, R.string.permission_required, Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets()
        if (!BuildConfig.IS_PLAY_STORE) {
            DedicatedMode.apply(this)
        }
        binding.toolbar.subtitle = "v${Catalog.APP_VERSION}"

        val playBackCallback = object : OnBackPressedCallback(!BuildConfig.IS_PLAY_STORE) {
            override fun handleOnBackPressed() {
                if (!BuildConfig.IS_PLAY_STORE) {
                    finishAffinity()
                } else if (binding.bottomNav.selectedItemId != R.id.nav_capture) {
                    binding.bottomNav.selectedItemId = R.id.nav_capture
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, playBackCallback)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            if (BuildConfig.IS_PLAY_STORE) {
                playBackCallback.isEnabled = item.itemId != R.id.nav_capture
            }
            when (item.itemId) {
                R.id.nav_capture -> show(CaptureFragment())
                R.id.nav_gallery -> show(GalleryFragment())
                R.id.nav_stats -> show(StatsFragment())
                R.id.nav_settings -> show(SettingsFragment())
                else -> false
            }
        }
        if (savedInstanceState == null) {
            binding.bottomNav.selectedItemId = R.id.nav_capture
        }
        registerDeviceQuietly()
    }

    private fun applySystemBarInsets() {
        val rootPadL = binding.root.paddingLeft
        val rootPadR = binding.root.paddingRight
        val toolbarPadTop = binding.toolbar.paddingTop
        val bottomPadBottom = binding.bottomNav.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            v.setPadding(rootPadL + bars.left, 0, rootPadR + bars.right, 0)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.toolbar) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            v.setPadding(v.paddingLeft, toolbarPadTop + bars.top, v.paddingRight, v.paddingBottom)
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.bottomNav) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, bottomPadBottom + bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun registerDeviceQuietly() {
        val url = SessionPrefs.serverUrl(this)
        if (url.isBlank()) return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                UploadClient.syncFromHub(this@MainActivity, url)
                UpdateClient.registerDevice(url, DeviceId.devicePayload(this@MainActivity))
            }
        }
    }

    private fun show(fragment: Fragment): Boolean {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentHost, fragment)
            .commit()
        return true
    }

    override fun onResume() {
        super.onResume()
        if (!BuildConfig.IS_PLAY_STORE) {
            DedicatedMode.hideSystemBars(this)
        }
    }
}
