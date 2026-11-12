package com.everyonesoftware.audio

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.request.CachePolicy
import okhttp3.OkHttpClient

class AudioApplication : Application(), ImageLoaderFactory {
    val graph: AudioGraph by lazy { AudioGraph(this) }
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient(OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).cache(null).build())
        .diskCache { null }.memoryCache { null }
        .diskCachePolicy(CachePolicy.DISABLED).memoryCachePolicy(CachePolicy.DISABLED)
        .build()
}
