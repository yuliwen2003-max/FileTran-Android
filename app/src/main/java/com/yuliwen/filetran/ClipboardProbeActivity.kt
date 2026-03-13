package com.yuliwen.filetran

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager

class ClipboardProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureTinyTransparentWindow()
        overridePendingTransition(0, 0)
        finishSilently()
    }

    override fun onResume() {
        super.onResume()
        finishSilently()
    }

    override fun onPause() {
        super.onPause()
        if (!isFinishing) {
            finishSilently()
        }
    }

    private fun finishSilently() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            finishAndRemoveTask()
        } else {
            finish()
        }
        overridePendingTransition(0, 0)
    }

    private fun configureTinyTransparentWindow() {
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        val lp = window.attributes
        lp.width = 1
        lp.height = 1
        lp.gravity = Gravity.START or Gravity.TOP
        lp.x = 0
        lp.y = 0
        lp.dimAmount = 0f
        lp.windowAnimations = 0
        window.attributes = lp
    }

}
