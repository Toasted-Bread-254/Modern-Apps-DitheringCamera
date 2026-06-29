package com.vayunmathur.weather.util

import com.vayunmathur.library.util.BaseBackupAgent
import com.vayunmathur.weather.data.weatherDbConfigs

class AppBackupAgent : BaseBackupAgent() {
    override val dbConfigs: List<Pair<String, String>>
        get() = weatherDbConfigs(this)
}
