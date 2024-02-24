package io.github.jan.supabase.common

import io.ktor.utils.io.ByteReadChannel

actual class MPFile {
    actual val name: String
        get() = TODO("Not yet implemented")
    actual val extension: String
        get() = TODO("Not yet implemented")
    actual val source: String
        get() = TODO("Not yet implemented")
    actual val size: Long
        get() = TODO("Not yet implemented")
    actual val dataProducer: suspend (offset: Long) -> ByteReadChannel
        get() = TODO("Not yet implemented")

}