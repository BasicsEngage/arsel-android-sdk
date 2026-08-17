package sa.arsel.core.model

import org.json.JSONArray
import org.json.JSONObject

/** How a queued request proves who it is. Resolved at drain time, never baked into the body. */
internal enum class AuthMode {
    /** Registration only — it is the call that mints the device secret. */
    NONE,

    /** `X-Arsel-Device-Auth`, the per-installation secret. The push API. */
    DEVICE,

    /** `Authorization: Bearer pub_…`, the publishable client key. The events API. */
    CLIENT_KEY,
}

/**
 * A pending backend call (register / unsubscribe / state / engagement / event), persisted until
 * delivered.
 *
 * [authMode] is a flag rather than a baked-in header: credentials are attached at drain time, so a
 * request enqueued before registration completes still authenticates, and a rotated secret is never
 * replayed from disk.
 *
 * [commitHash] is the deferred half of registration bookkeeping. It is applied only when the drain
 * sees a confirmed 2xx — committing it at enqueue would leave the SDK believing it had registered
 * after a request that never reached the server.
 */
internal data class QueuedRequest(
    val id: String,
    val path: String,
    val body: String,
    val dedupeKey: String?,
    val authMode: AuthMode,
    val createdAtMs: Long,
    /** Written to `lastRegisteredHash` on success, so an unchanged device is never re-reported. */
    val commitHash: String? = null,
) {
    /** Whether a 401/403/404 means "this credential is dead" rather than "this route is wrong". */
    val requiresDeviceAuth: Boolean get() = authMode == AuthMode.DEVICE

    fun toJson(): JSONObject =
        JSONObject()
            .put(FIELD_ID, id)
            .put(FIELD_PATH, path)
            .put(FIELD_BODY, body)
            .put(FIELD_DEDUPE_KEY, dedupeKey ?: JSONObject.NULL)
            .put(FIELD_AUTH_MODE, authMode.name)
            .put(FIELD_CREATED_AT, createdAtMs)
            .put(FIELD_COMMIT_HASH, commitHash ?: JSONObject.NULL)

    companion object {
        private const val FIELD_ID = "id"
        private const val FIELD_PATH = "path"
        private const val FIELD_BODY = "body"
        private const val FIELD_DEDUPE_KEY = "dedupeKey"
        private const val FIELD_AUTH_MODE = "authMode"

        /** Pre-[AuthMode] shape. Still read so an upgrade does not strand a queued request. */
        private const val FIELD_REQUIRES_DEVICE_AUTH = "requiresDeviceAuth"
        private const val FIELD_CREATED_AT = "createdAtMs"
        private const val FIELD_COMMIT_HASH = "commitHash"

        private fun readAuthMode(o: JSONObject): AuthMode {
            val named = if (o.isNull(FIELD_AUTH_MODE)) null else o.optString(FIELD_AUTH_MODE)
            AuthMode.entries.firstOrNull { it.name == named }?.let { return it }
            return if (o.optBoolean(FIELD_REQUIRES_DEVICE_AUTH, false)) AuthMode.DEVICE else AuthMode.NONE
        }

        private fun fromJson(o: JSONObject) =
            QueuedRequest(
                id = o.getString(FIELD_ID),
                path = o.getString(FIELD_PATH),
                body = o.getString(FIELD_BODY),
                dedupeKey = if (o.isNull(FIELD_DEDUPE_KEY)) null else o.getString(FIELD_DEDUPE_KEY),
                authMode = readAuthMode(o),
                createdAtMs = o.optLong(FIELD_CREATED_AT, 0L),
                commitHash = if (o.isNull(FIELD_COMMIT_HASH)) null else o.getString(FIELD_COMMIT_HASH),
            )

        fun listFromJson(json: String?): List<QueuedRequest> {
            if (json.isNullOrBlank()) return emptyList()
            return runCatching {
                val arr = JSONArray(json)
                (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
            }.getOrDefault(emptyList())
        }

        fun listToJson(list: List<QueuedRequest>): String {
            val arr = JSONArray()
            list.forEach { arr.put(it.toJson()) }
            return arr.toString()
        }
    }
}
