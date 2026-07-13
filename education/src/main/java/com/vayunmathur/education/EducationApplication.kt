package com.vayunmathur.education

import android.app.Application
import com.vayunmathur.education.util.EducationDownloader
import org.schabi.newpipe.extractor.NewPipe

class EducationApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        NewPipe.init(EducationDownloader())
    }
}
