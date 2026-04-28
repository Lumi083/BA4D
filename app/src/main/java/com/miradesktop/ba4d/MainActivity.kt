package com.miradesktop.ba4d

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
    }
}
