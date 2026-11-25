package com.anddesign.vectorviewer

import android.app.Application
import com.anddesign.vectorviewer.data.OneDrive
import com.anddesign.vectorviewer.data.Repos

class VectorViewerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Repos.init(this)
        OneDrive.init(this)
    }
}
