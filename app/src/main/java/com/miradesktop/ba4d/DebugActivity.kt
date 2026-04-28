package com.miradesktop.ba4d

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.miradesktop.ba4d.databinding.ActivityDebugBinding

class DebugActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDebugBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDebugBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "调试"

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.debugContainer, DebugFragment())
                .commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
