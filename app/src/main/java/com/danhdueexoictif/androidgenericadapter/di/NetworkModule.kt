package com.danhdueexoictif.androidgenericadapter.di

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Base64
import com.danhdueexoictif.androidgenericadapter.BuildConfig
import com.danhdueexoictif.androidgenericadapter.data.remote.*
import com.danhdueexoictif.androidgenericadapter.di.NetworkModule.createApiService
import com.danhdueexoictif.androidgenericadapter.di.NetworkModule.createAppRetrofit
import com.danhdueexoictif.androidgenericadapter.di.NetworkModule.createBaseAuthInterceptor
import com.danhdueexoictif.androidgenericadapter.di.NetworkModule.createLoggingInterceptor
import com.danhdueexoictif.androidgenericadapter.di.NetworkModule.createOkHttpCache
import com.danhdueexoictif.androidgenericadapter.di.NetworkModule.createOkHttpClient
import com.danhdueexoictif.androidgenericadapter.utils.Constants
import com.danhdueexoictif.androidgenericadapter.utils.networkdetection.NoInternetUtils.isNetworkAvailable
import okhttp3.Cache
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.koin.core.KoinComponent
import org.koin.core.qualifier.named
import org.koin.dsl.module
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.converter.scalars.ScalarsConverterFactory
import java.util.concurrent.TimeUnit

val networkModule = module {
    single { createOkHttpCache(get()) }
    single(named("logging")) { createLoggingInterceptor() }
    single(named("basicAuth")) {
        createBaseAuthInterceptor(
            get(),
            BuildConfig.BASE_AUTH_USER_NAME,
            BuildConfig.BASE_AUTH_PASSWORD
        )
    }
    single<Interceptor>(named("mockInterceptor")) { MockInterceptor(get()) }
    single { ChangeableBaseUrlInterceptor() }
    single { NetworkAdapterFactory() }
    single {
        createOkHttpClient(
            get(), get(named("logging")), get(named("basicAuth")), get(), get(
                named("mockInterceptor")
            )
        )
    }
    single { createAppRetrofit(get(), get(), true) }
    single { createApiService(get()) }
}

object NetworkModule : KoinComponent {

    private const val TIMEOUT = 10

    fun createOkHttpCache(context: Context): Cache =
        Cache(context.cacheDir, (10 * 1024 * 1024).toLong())

    fun createLoggingInterceptor(): Interceptor = HttpLoggingInterceptor().apply {
        level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY
        else HttpLoggingInterceptor.Level.NONE
    }

    fun createBaseAuthInterceptor(
        context: Context,
        userName: String,
        password: String
    ): Interceptor =
        Interceptor { chain ->
            val credentials = "$userName:$password"
            val basicAuth =
                "Basic ${Base64.encodeToString(credentials.toByteArray(), Base64.NO_WRAP)}"
            val request = chain.request()
            val newRequest = request.newBuilder().apply {
                url(request.url.newBuilder().build())
                header(Constants.NetworkRequestParam.AUTHORIZATION, basicAuth)
                header(
                    Constants.NetworkRequestParam.CONTENT_TYPE,
                    Constants.NetworkRequestValue.CONTENT_TYPE
                )
                method(request.method, request.body)
            }.build()
            if (!isNetworkAvailable(context)) {
                throw NoConnectivityException()
            }
            chain.proceed(newRequest)
        }

    fun createOkHttpClient(
        cache: Cache?,
        logging: Interceptor,
        basicAuthInterceptor: Interceptor,
        changeableBaseUrlInterceptor: ChangeableBaseUrlInterceptor,
        mockInterceptor: Interceptor
    ): OkHttpClient = OkHttpClient.Builder().apply {
        connectTimeout(TIMEOUT.toLong(), TimeUnit.SECONDS)
        readTimeout(TIMEOUT.toLong(), TimeUnit.SECONDS)
        addInterceptor(basicAuthInterceptor)
        addInterceptor(logging)
        addInterceptor(changeableBaseUrlInterceptor)
        // mock data for the APIs while a particular API is not implemented yet on Backend side.
        if (BuildConfig.DEBUG) addInterceptor(mockInterceptor)
        if (cache != null) {
            cache(cache)
        }
    }.build()

    fun createAppRetrofit(
        okHttpClient: OkHttpClient,
        networkAdapterFactory: NetworkAdapterFactory,
        isErrorHandling: Boolean
    ): Retrofit = Retrofit.Builder().apply {
        if (isErrorHandling) {
            addCallAdapterFactory(networkAdapterFactory)
        }
        addConverterFactory(ScalarsConverterFactory.create())
        addConverterFactory(GsonConverterFactory.create())
        baseUrl(BuildConfig.CONFIGURATION_DOMAIN)
        client(okHttpClient)
    }.build()

    fun createApiService(retrofit: Retrofit): ApiService {
        return retrofit.create(ApiService::class.java)
    }
}
