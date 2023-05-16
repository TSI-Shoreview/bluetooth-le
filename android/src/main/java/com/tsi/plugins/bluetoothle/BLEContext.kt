package com.tsi.plugins.bluetoothle

class BLEContext {
    private var kv = HashMap<String, Any>()

    fun <T> Get(key: String): T? {
        return kv[key] as T?
    }

    fun Set(key: String, value: Any) {
        kv[key] = value
    }
}