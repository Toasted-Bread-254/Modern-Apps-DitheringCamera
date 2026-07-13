package com.vayunmathur.youpipe

import android.app.Application
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.youpipe.util.MyDownloader
import com.vayunmathur.youpipe.util.youtubeLocalization
import org.schabi.newpipe.extractor.NewPipe

class YouPipeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val language = DataStoreUtils.getInstance(this).getString("youtube_language").orEmpty()
        NewPipe.init(MyDownloader(), youtubeLocalization(language))
    }
}
