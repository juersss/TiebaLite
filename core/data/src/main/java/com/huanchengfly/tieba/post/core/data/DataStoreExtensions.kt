package com.huanchengfly.tieba.post.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

fun DataStore<Preferences>.putString(key: String, value: String? = null) {
    MainScope().launch(Dispatchers.IO) {
        edit {
            if (value == null) {
                it.remove(stringPreferencesKey(key))
            } else {
                it[stringPreferencesKey(key)] = value
            }
        }
    }
}

fun DataStore<Preferences>.putBoolean(key: String, value: Boolean) {
    MainScope().launch(Dispatchers.IO) {
        edit {
            it[booleanPreferencesKey(key)] = value
        }
    }
}

fun DataStore<Preferences>.putInt(key: String, value: Int) {
    MainScope().launch(Dispatchers.IO) {
        edit {
            it[intPreferencesKey(key)] = value
        }
    }
}

fun DataStore<Preferences>.getInt(key: String, defaultValue: Int): Int {
    var resultValue = defaultValue

    runBlocking {
        data.first {
            resultValue = it[intPreferencesKey(key)] ?: resultValue
            true
        }
    }

    return resultValue
}

fun DataStore<Preferences>.getString(key: String): String? {
    var resultValue: String? = null

    runBlocking {
        data.first {
            resultValue = it[stringPreferencesKey(key)]
            true
        }
    }

    return resultValue
}

fun DataStore<Preferences>.getString(key: String, defaultValue: String): String {
    var resultValue = defaultValue

    runBlocking {
        data.first {
            resultValue = it[stringPreferencesKey(key)] ?: resultValue
            true
        }
    }

    return resultValue
}

fun DataStore<Preferences>.getStringSet(
    key: String,
    defaultValues: MutableSet<String>? = null
): MutableSet<String>? {
    var resultValue = defaultValues

    runBlocking {
        data.first {
            resultValue = it[stringSetPreferencesKey(key)]?.toMutableSet() ?: resultValue
            true
        }
    }

    return resultValue
}

fun DataStore<Preferences>.getBoolean(key: String, defaultValue: Boolean): Boolean {
    var resultValue = defaultValue

    runBlocking {
        data.first {
            resultValue = it[booleanPreferencesKey(key)] ?: resultValue
            true
        }
    }

    return resultValue
}

fun DataStore<Preferences>.getFloat(key: String, defaultValue: Float): Float {
    var resultValue = defaultValue

    runBlocking {
        data.first {
            resultValue = it[floatPreferencesKey(key)] ?: resultValue
            true
        }
    }

    return resultValue
}

fun DataStore<Preferences>.getLong(key: String, defaultValue: Long): Long {
    var resultValue = defaultValue

    runBlocking {
        data.first {
            resultValue = it[longPreferencesKey(key)] ?: resultValue
            true
        }
    }

    return resultValue
}
