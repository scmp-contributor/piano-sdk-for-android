package io.piano.android.composer

import android.content.Context
import io.piano.android.composer.listeners.EventTypeListener
import io.piano.android.composer.listeners.ExceptionListener
import io.piano.android.composer.model.Data
import io.piano.android.composer.model.Event
import io.piano.android.composer.model.ExperienceRequest
import io.piano.android.composer.model.ExperienceResponse
import io.piano.android.composer.model.events.EventType
import io.piano.android.composer.model.events.ShowTemplate
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.Callback
import retrofit2.HttpException
import retrofit2.Response

class Composer internal constructor(
    private val composerApi: ComposerApi,
    private val generalApi: GeneralApi,
    private val httpHelper: HttpHelper,
    private val prefsStorage: PrefsStorage,
    private val aid: String,
    private val endpoint: Endpoint
) {
    private val templateUrl by lazy {
        endpoint.apiHost.newBuilder().addPathSegments(URL_TEMPLATE).build()
    }
    private var browserIdProvider: () -> String? = { null }
    private var userToken: String? = null
    private var gaClientId: String? = null
    private val experienceInterceptors = mutableListOf<ExperienceInterceptor>(httpHelper)

    init {
        require(aid.isNotEmpty()) {
            "AID can't be empty"
        }
    }

    /**
     * Gets Composer's user access token for Edge CDN
     * @return Access token for Edge CDN
     */
    val accessToken: String
        get() = prefsStorage.tpAccessCookie

    fun addExperienceInterceptor(interceptor: ExperienceInterceptor) = experienceInterceptors.add(interceptor)

    fun browserIdProvider(browserIdProvider: () -> String?) = apply { this.browserIdProvider = browserIdProvider }

    /**
     * Sets user token, which will be sent at each experience request
     * @param userToken User token
     * @return Composer instance
     */
    @Suppress("unused") // Public API.
    fun userToken(userToken: String?) = apply { this.userToken = userToken }

    /**
     * Sets Google Analytics client id
     *
     * @param gaClientId Client id
     * @return Composer instance
     */
    @Suppress("unused") // Public API.
    fun gaClientId(gaClientId: String?) = apply { this.gaClientId = gaClientId }

    /**
     * Gets experience from server
     *
     * @param request            Prepared experience request
     * @param eventTypeListeners Collection of event listeners
     * @param exceptionListener  Listener for exceptions
     */
    @Suppress("unused") // Public API.
    fun getExperience(
        request: ExperienceRequest,
        eventTypeListeners: Collection<EventTypeListener<out EventType>>,
        exceptionListener: ExceptionListener
    ) {
        experienceInterceptors.forEach { it.beforeExecute(request) }
        composerApi.getExperience(
            httpHelper.convertExperienceRequest(request, aid, browserIdProvider, userToken)
        ).enqueue(
            object : Callback<Data<ExperienceResponse>> {
                override fun onResponse(
                    call: Call<Data<ExperienceResponse>>,
                    response: Response<Data<ExperienceResponse>>
                ) {
                    runCatching {
                        with(response.bodyOrThrow()) {
                            if (errors.isNotEmpty()) {
                                throw ComposerException(errors.joinToString(separator = "\n") { it.message })
                            }

                            processExperienceResponse(
                                request,
                                data,
                                eventTypeListeners,
                                exceptionListener
                            )
                        }
                    }.onFailure {
                        exceptionListener.onException(it.toComposerException())
                    }
                }

                override fun onFailure(call: Call<Data<ExperienceResponse>>, t: Throwable) =
                    exceptionListener.onException(t.toComposerException())
            }
        )
    }

    /**
     * Tracks external event by id
     *
     * @param trackingId Tracking id
     */
    @Suppress("unused") // Public API.
    fun trackExternalEvent(trackingId: String) {
        generalApi.trackExternalEvent(httpHelper.buildEventTracking(trackingId)).enqueue(emptyCallback)
    }

    /**
     * Tracks custom form impression by name
     *
     * @param customFormName Custom form name
     */
    @Suppress("unused") // Public API.
    fun trackCustomFormImpression(customFormName: String, trackingId: String) =
        generalApi.customFormImpression(httpHelper.buildCustomFormTracking(aid, customFormName, trackingId, userToken))
            .enqueue(emptyCallback)

    /**
     * Tracks custom form submission by name
     *
     * @param customFormName Custom form name
     */
    @Suppress("unused") // Public API.
    fun trackCustomFormSubmission(customFormName: String, trackingId: String) =
        generalApi.customFormSubmission(httpHelper.buildCustomFormTracking(aid, customFormName, trackingId, userToken))
            .enqueue(emptyCallback)

    /**
     * Clears stored data, like cookies, visit data
     */
    @Suppress("unused") // Public API.
    fun clearStoredData() = prefsStorage.clear()

    internal fun processExperienceResponse(
        request: ExperienceRequest,
        response: ExperienceResponse,
        eventTypeListeners: Collection<EventTypeListener<out EventType>>,
        exceptionListener: ExceptionListener
    ) {
        experienceInterceptors.forEach { it.afterExecute(request, response) }

        // Don't process any custom logic if there's no listeners
        if (eventTypeListeners.isEmpty())
            return

        response.result.events.forEach {
            val event = it.preprocess(request)
            eventTypeListeners.forEach { listener ->
                if (listener.canProcess(it)) {
                    runCatching {
                        @Suppress("UNCHECKED_CAST")
                        (listener as EventTypeListener<EventType>).onExecuted(event)
                    }.onFailure {
                        exceptionListener.onException(it.toComposerException())
                    }
                }
            }
        }
    }

    @Suppress("NOTHING_TO_INLINE")
    private inline fun Event<EventType>.preprocess(request: ExperienceRequest): Event<EventType> {
        val data = if (eventData is ShowTemplate) {
            val builder = templateUrl.newBuilder()
            @Suppress("UNCHECKED_CAST")
            httpHelper.buildShowTemplateParameters(
                this as Event<ShowTemplate>,
                request,
                aid,
                userToken,
                gaClientId
            ).forEach { (key, value) ->
                builder.addQueryParameter(key, value)
            }
            eventData.copy(url = builder.build().toString())
        } else eventData
        return copy(
            eventData = data
        )
    }

    private fun <T> Response<T>.bodyOrThrow(): T {
        if (!isSuccessful)
            throw ComposerException(HttpException(this))
        return body() ?: throw ComposerException()
    }

    @Suppress("NOTHING_TO_INLINE")
    private inline fun Throwable.toComposerException() =
        if (this is ComposerException) this else ComposerException(this)

    class Endpoint(
        composerHost: String,
        apiHost: String
    ) {
        internal val composerHost: HttpUrl = HttpUrl.get(composerHost)
        internal val apiHost: HttpUrl = HttpUrl.get(apiHost)

        companion object {
            private const val COMPOSER_SANDBOX_URL = "https://c2.sandbox.piano.io"
            private const val API_SANDBOX_URL = "https://sandbox.piano.io"
            private const val COMPOSER_DEFAULT_URL = "https://c2.piano.io"
            private const val API_DEFAULT_URL = "https://buy.piano.io"
            private const val COMPOSER_AU_URL = "https://c2-au.piano.io"
            private const val API_AU_URL = "https://buy-au.piano.io"
            private const val COMPOSER_AP_URL = "https://c2-ap.piano.io"
            private const val API_AP_URL = "https://buy-ap.piano.io"
            private const val COMPOSER_EU_URL = "https://c2-eu.piano.io"
            private const val API_EU_URL = "https://buy-eu.piano.io"

            /**
             * Sandbox endpoint
             */
            @JvmField
            @Suppress("unused") // Public API.
            val SANDBOX = Endpoint(COMPOSER_SANDBOX_URL, API_SANDBOX_URL)

            /**
             * Default production endpoint
             */
            @JvmField
            @Suppress("unused") // Public API.
            val PRODUCTION = Endpoint(COMPOSER_DEFAULT_URL, API_DEFAULT_URL)

            /**
             * Australia production endpoint
             */
            @JvmField
            @Suppress("unused") // Public API.
            val PRODUCTION_AUSTRALIA = Endpoint(COMPOSER_AU_URL, API_AU_URL)

            /**
             * Asia/Pacific production endpoint
             */
            @JvmField
            @Suppress("unused") // Public API.
            val PRODUCTION_ASIA_PACIFIC = Endpoint(COMPOSER_AP_URL, API_AP_URL)

            /**
             * Europe production endpoint
             */
            @JvmField
            @Suppress("unused") // Public API.
            val PRODUCTION_EUROPE = Endpoint(COMPOSER_EU_URL, API_EU_URL)
        }
    }

    companion object {
        /**
         * Initialize Composer
         * @param context Activity or Application context
         * @param aid Your AID
         * @param endpoint Custom API endpoint, see predefined in {@link Composer.Endpoint}
         */
        @JvmStatic
        @JvmOverloads
        @Suppress("unused") // Public API.
        fun init(context: Context, aid: String, endpoint: Endpoint = Endpoint.PRODUCTION) =
            DependenciesProvider.init(context, aid, endpoint)

        @JvmStatic
        @Suppress("unused") // Public API.
        fun getInstance(): Composer = DependenciesProvider.getInstance().composer

        @Suppress("unused") // Public API.
        const val USER_PROVIDER_TINYPASS_ACCOUNTS = "tinypass_accounts"

        @Suppress("unused") // Public API.
        const val USER_PROVIDER_PIANO_ID = "piano_id"

        @Suppress("unused") // Public API.
        const val USER_PROVIDER_JANRAIN = "janrain"

        private const val URL_TEMPLATE = "checkout/template/show"
        private val emptyCallback = object : Callback<ResponseBody> {
            override fun onResponse(call: Call<ResponseBody>, response: Response<ResponseBody>) {}
            override fun onFailure(call: Call<ResponseBody>, t: Throwable) {}
        }
    }
}
