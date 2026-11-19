package com.vasthread.webviewtv.app

import android.app.Application
import android.os.Build
import android.util.Log
import com.tencent.bugly.crashreport.CrashReport
import com.tencent.smtt.export.external.TbsCoreSettings
import com.tencent.smtt.sdk.QbSdk
import com.tencent.smtt.sdk.QbSdk.PreInitCallback
import com.vasthread.webviewtv.misc.setApplication
import com.vasthread.webviewtv.settings.SettingsManager
import me.jessyan.autosize.AutoSizeConfig

class LiveApplication : Application() {

    companion object {
        private const val TAG = "LiveApplication"
    }

    override fun onCreate() {
        super.onCreate()
        setApplication(this)
        initAutoSize()
        initCrashReport()
        initX5()
    }

    private fun initAutoSize() {
        AutoSizeConfig.getInstance().apply {
            setExcludeFontScale(true)
            // The overlays are laid out for a 960 x 540 dp screen: the channel list is 275 dp wide,
            // the exit panel 200 dp and the settings panel 400 dp. The library assumes a 360 dp
            // phone by default, which on a TV blows every dp up about 2.7 times, so the exit panel
            // ends up taller than the screen and its buttons fall out of it.
            setDesignWidthInDp(960)
            setDesignHeightInDp(540)
        }
    }

    private fun initX5() {
        val settings = mutableMapOf<String, Any>(
            TbsCoreSettings.TBS_SETTINGS_USE_SPEEDY_CLASSLOADER to true,
            TbsCoreSettings.TBS_SETTINGS_USE_DEXLOADER_SERVICE to true,
        )
        QbSdk.initTbsSettings(settings)
        QbSdk.initX5Environment(this, object : PreInitCallback {
            override fun onCoreInitFinished() {
                Log.i(TAG, "onCoreInitFinished")
            }

            override fun onViewInitFinished(isX5: Boolean) {
                Log.i(TAG, "onViewInitFinished, isX5=$isX5")
            }
        })
    }

    private fun initCrashReport() {
        CrashReport.initCrashReport(this, "5115c8a378", false)
        CrashReport.setUserId(this, SettingsManager.getUserId())
        CrashReport.setDeviceModel(this, "${Build.MANUFACTURER}/${Build.MODEL}/${Build.PRODUCT}")
    }
}