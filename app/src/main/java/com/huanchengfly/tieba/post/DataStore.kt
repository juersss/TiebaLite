package com.huanchengfly.tieba.post

import android.annotation.SuppressLint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.preference.PreferenceDataStore
import com.huanchengfly.tieba.post.core.data.dataStore
import com.huanchengfly.tieba.post.core.data.getBoolean
import com.huanchengfly.tieba.post.core.data.getFloat
import com.huanchengfly.tieba.post.core.data.getInt
import com.huanchengfly.tieba.post.core.data.getLong
import com.huanchengfly.tieba.post.core.data.getString
import com.huanchengfly.tieba.post.core.data.getStringSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

// Phase 5（2026-09-17）：`Context.dataStore` 委托、get/put 扩展与 DataStoreConst 已迁
// core:data（包 com.huanchengfly.tieba.post.core.data），本文件保留 app 特有的两件：
// ① Compose 辅助（rememberPreferenceAsState 等，依赖 LocalContext）；
// ② DataStorePreference（androidx.preference 桥接，用 App.INSTANCE，core:data 不得依赖 app 故留此）。

@Composable
fun <T> rememberPreferenceAsMutableState(
    key: Preferences.Key<T>,
    defaultValue: T
): MutableState<T> {
    val dataStore = LocalContext.current.dataStore
    val state = remember { mutableStateOf(defaultValue) }

    LaunchedEffect(Unit) {
        dataStore.data.map { it[key] ?: defaultValue }.distinctUntilChanged()
            .collect { state.value = it }
    }

    LaunchedEffect(state.value) {
        dataStore.edit { it[key] = state.value }
    }

    return state
}

@Composable
fun <T> rememberPreferenceAsState(
    key: Preferences.Key<T>,
    defaultValue: T
): State<T> {
    val dataStore = LocalContext.current.dataStore
    val state = remember { mutableStateOf(defaultValue) }

    LaunchedEffect(Unit) {
        dataStore.data.map { it[key] ?: defaultValue }.distinctUntilChanged()
            .collect { state.value = it }
    }

    LaunchedEffect(state.value) {
        dataStore.edit { it[key] = state.value }
    }

    return state
}

@SuppressLint("FlowOperatorInvokedInComposition")
@Composable
fun <T> DataStore<Preferences>.collectPreferenceAsState(
    key: Preferences.Key<T>,
    defaultValue: T
): State<T> {
    return data.map { it[key] ?: defaultValue }.collectAsState(initial = defaultValue)
}

class DataStorePreference : PreferenceDataStore() {
    override fun putString(key: String, value: String?) {
        MainScope().launch(Dispatchers.IO) {
            App.INSTANCE.dataStore.edit {
                if (value == null) {
                    it.remove(stringPreferencesKey(key))
                } else {
                    it[stringPreferencesKey(key)] = value
                }
            }
        }
    }

    override fun putStringSet(key: String, values: MutableSet<String>?) {
        MainScope().launch(Dispatchers.IO) {
            App.INSTANCE.dataStore.edit {
                if (values == null) {
                    it.remove(stringSetPreferencesKey(key))
                } else {
                    it[stringSetPreferencesKey(key)] = values
                }
            }
        }
    }

    override fun putInt(key: String, value: Int) {
        MainScope().launch(Dispatchers.IO) {
            App.INSTANCE.dataStore.edit {
                it[intPreferencesKey(key)] = value
            }
        }
    }

    override fun putLong(key: String, value: Long) {
        MainScope().launch(Dispatchers.IO) {
            App.INSTANCE.dataStore.edit {
                it[longPreferencesKey(key)] = value
            }
        }
    }

    override fun putFloat(key: String, value: Float) {
        MainScope().launch(Dispatchers.IO) {
            App.INSTANCE.dataStore.edit {
                it[floatPreferencesKey(key)] = value
            }
        }
    }

    override fun putBoolean(key: String, value: Boolean) {
        MainScope().launch(Dispatchers.IO) {
            App.INSTANCE.dataStore.edit {
                it[booleanPreferencesKey(key)] = value
            }
        }
    }

    override fun getString(key: String, defValue: String?): String? {
        return App.INSTANCE.dataStore.getString(key) ?: defValue
    }

    override fun getStringSet(
        key: String,
        defValues: MutableSet<String>?
    ): MutableSet<String>? {
        return App.INSTANCE.dataStore.getStringSet(key, defValues)
    }

    override fun getInt(key: String, defValue: Int): Int {
        return App.INSTANCE.dataStore.getInt(key, defValue)
    }

    override fun getLong(key: String, defValue: Long): Long {
        return App.INSTANCE.dataStore.getLong(key, defValue)
    }

    override fun getFloat(key: String, defValue: Float): Float {
        return App.INSTANCE.dataStore.getFloat(key, defValue)
    }

    override fun getBoolean(key: String, defValue: Boolean): Boolean {
        return App.INSTANCE.dataStore.getBoolean(key, defValue)
    }
}
