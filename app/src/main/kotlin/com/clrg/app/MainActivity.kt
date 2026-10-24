package com.clrg.app

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import com.clrg.core.CoreInfo

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = CoreInfo.NAME; textSize = 32f })
    }
}
