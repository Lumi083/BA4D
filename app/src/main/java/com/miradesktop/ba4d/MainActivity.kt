package com.miradesktop.ba4d

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.bottomnavigation.BottomNavigationView
import androidx.viewpager2.widget.ViewPager2
import com.miradesktop.ba4d.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    fun openDebugPage() {
        val intent = Intent(this, DebugActivity::class.java)
        startActivity(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = 3
            override fun createFragment(position: Int) = when (position) {
                0 -> ContentFragment()
                1 -> HomeFragment()
                2 -> AboutFragment()
                else -> HomeFragment()
            }
        }

        binding.viewPager.setCurrentItem(1, false)
        binding.bottomNav.selectedItemId = R.id.nav_home

        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                binding.bottomNav.menu.getItem(position).isChecked = true
            }
        })

        binding.bottomNav.setOnItemSelectedListener {
            binding.viewPager.currentItem = when (it.itemId) {
                R.id.nav_content -> 0
                R.id.nav_home -> 1
                R.id.nav_about -> 2
                else -> 1
            }
            true
        }

        // Apply hide from recents preference on startup
        applyHideFromRecentsPreference()
    }

    override fun onStart() {
        super.onStart()
        android.util.Log.d("MainActivity", "onStart - sending APP_FOREGROUND broadcast")
        sendBroadcast(Intent("com.miradesktop.ba4d.APP_FOREGROUND"))
    }

    override fun onStop() {
        super.onStop()
        android.util.Log.d("MainActivity", "onStop - sending APP_BACKGROUND broadcast")
        sendBroadcast(Intent("com.miradesktop.ba4d.APP_BACKGROUND"))
    }

    private fun applyHideFromRecentsPreference() {
        try {
            val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val hideFromRecents = prefs.getBoolean("hide_from_recents", false)
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            activityManager.appTasks.firstOrNull()?.setExcludeFromRecents(hideFromRecents)
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Failed to apply excludeFromRecents preference", e)
        }
    }
}
