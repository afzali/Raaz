package io.raaz.messenger.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import io.raaz.messenger.data.db.RaazDatabase
import io.raaz.messenger.data.db.dao.SettingsDao
import io.raaz.messenger.data.model.AppSettings

class SharedViewModel(app: Application) : AndroidViewModel(app) {

    private val _dbKey = MutableLiveData<String>()
    val dbKey: LiveData<String> = _dbKey

    private var _db: RaazDatabase? = null

    fun setDbKey(key: String) {
        _dbKey.value = key
        _db = RaazDatabase.getInstance(getApplication(), key)
    }

    fun getDb(): RaazDatabase? = _db

    fun getDbKey(): String? = _dbKey.value

    fun getSettings(): AppSettings? = _db?.let { SettingsDao(it.db).get() }
}
