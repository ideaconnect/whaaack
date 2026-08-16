package tech.idct.whaaack.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The 401 refresh-and-retry, against a real HTTP server.
 *
 * This is the recovery path for a token the server will not take, and it was wrong in two
 * ways in a row that no amount of reading caught for long: first the "refresh" politely
 * handed back the very token that had just been rejected (so the retry was a guaranteed
 * second 401 and a rotated JWT secret would have signed out every install), then a refresh
 * that failed for a *transient* reason — an outage, a rate limit — read the same as a dead
 * refresh token and destroyed the stored session. Both are arithmetic about state the server
 * disagrees with, which is exactly what a test against a real socket pins down.
 */
class SupabaseClientTest {

    private lateinit var server: MockWebServer
    private lateinit var store: InMemorySessionStore
    private lateinit var client: SupabaseClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        store = InMemorySessionStore()
        client = SupabaseClient(
            baseUrl = server.url("/").toString().trimEnd('/'),
            anonKey = "anon-key",
            sessions = store,
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `a 401 spends the refresh token and retries with the minted one`() = runBlocking {
        // The crux: the *stored* expiry says this token is fine. Only the server disagrees —
        // a rotated JWT secret, a clock moved backwards — and that disagreement must force
        // the refresh rather than be talked out of it by the local arithmetic.
        store.session = session(access = "stale-access", expiresInMs = 3_600_000L)
        server.enqueue(unauthorized())
        server.enqueue(tokenResponse(access = "minted-access", refresh = "minted-refresh"))
        server.enqueue(MockResponse().setBody("[]"))

        val body = client.request("GET", "/rest/v1/profiles?select=display_name", authorized = true)

        assertEquals("[]", body)
        assertEquals(3, server.requestCount)

        val first = server.takeRequest()
        assertEquals("Bearer stale-access", first.getHeader("Authorization"))

        val refresh = server.takeRequest()
        assertTrue(refresh.path!!.contains("/auth/v1/token?grant_type=refresh_token"))
        assertTrue(refresh.body.readUtf8().contains("\"refresh_token\":\"good-refresh\""))

        val retry = server.takeRequest()
        assertEquals("Bearer minted-access", retry.getHeader("Authorization"))

        // The minted pair is what survives; the spent refresh token must not.
        assertEquals("minted-access", store.session?.accessToken)
        assertEquals("minted-refresh", store.session?.refreshToken)
    }

    @Test
    fun `a rejected refresh token ends the session and keeps the original 401`() = runBlocking {
        store.session = session()
        server.enqueue(unauthorized())
        server.enqueue(
            MockResponse().setResponseCode(400)
                .setBody("""{"error_code":"refresh_token_not_found","msg":"Invalid Refresh Token"}"""),
        )

        val thrown = assertThrows(SupabaseClient.SupabaseException::class.java) {
            runBlocking { client.request("GET", "/rest/v1/profiles", authorized = true) }
        }

        // The caller sees the 401 that started it — the signal AuthRepository reads as "this
        // session has already survived a refresh attempt and cannot be saved" — and the store
        // agrees with it.
        assertEquals(401, thrown.status)
        assertNull(store.session)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a token-endpoint outage leaves the session standing`() = runBlocking {
        // The refresh token is fine; the token endpoint is having a moment. Answering that
        // with null used to rethrow the original 401, which refreshProfile takes as a dead
        // session and *clears* — one 503 turned into a permanent sign-out.
        store.session = session()
        server.enqueue(unauthorized())
        server.enqueue(MockResponse().setResponseCode(503).setBody("service unavailable"))

        val thrown = assertThrows(SupabaseClient.SupabaseException::class.java) {
            runBlocking { client.request("GET", "/rest/v1/profiles", authorized = true) }
        }

        assertEquals(503, thrown.status)
        assertNotNull(store.session)
        assertEquals("good-refresh", store.session?.refreshToken)
    }

    @Test
    fun `the token endpoint's rate limit is an outage, not a rejection`() = runBlocking {
        // Supabase rate-limits /auth/v1/token. A 429 says "not now", never "never again".
        store.session = session()
        server.enqueue(unauthorized())
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"msg":"over_request_rate_limit"}"""))

        val thrown = assertThrows(SupabaseClient.SupabaseException::class.java) {
            runBlocking { client.request("GET", "/rest/v1/profiles", authorized = true) }
        }

        assertEquals(429, thrown.status)
        assertNotNull(store.session)
    }

    @Test
    fun `a token past its stored expiry is refreshed before the request goes out`() = runBlocking {
        store.session = session(access = "expired-access", expiresInMs = -1_000L)
        server.enqueue(tokenResponse(access = "minted-access", refresh = "minted-refresh"))
        server.enqueue(MockResponse().setBody("[]"))

        client.request("GET", "/rest/v1/profiles", authorized = true)

        // The refresh went first; the rejected-token dance never had to happen.
        val refresh = server.takeRequest()
        assertTrue(refresh.path!!.contains("grant_type=refresh_token"))
        val request = server.takeRequest()
        assertEquals("Bearer minted-access", request.getHeader("Authorization"))
    }

    @Test
    fun `an authorized request with no session fails as signed-out, not as the anon key`() = runBlocking {
        // The anon-key fallback used to go out and come back as 42501 "permission denied for
        // table profiles" — a message about the schema shown to a player whose actual
        // problem is that they are signed out.
        store.session = null

        val thrown = assertThrows(SupabaseClient.SupabaseException::class.java) {
            runBlocking { client.request("GET", "/rest/v1/profiles", authorized = true) }
        }

        assertEquals(401, thrown.status)
        assertEquals("session_missing", thrown.errorCode)
        assertEquals("nothing must reach the network", 0, server.requestCount)
    }

    @Test
    fun `a refresh keeps the cached display name the token endpoint knows nothing about`() = runBlocking {
        // profiles is the authority on the name; user_metadata lags it after every rename
        // and every trigger de-duplication. The refresh must carry the cache forward, not
        // regress it to the stale copy riding in the token response.
        store.session = session(access = "expired-access", expiresInMs = -1_000L)
            .copy(displayName = "Renamed In Settings")
        server.enqueue(tokenResponse(access = "minted-access", refresh = "minted-refresh"))
        server.enqueue(MockResponse().setBody("[]"))

        client.request("GET", "/rest/v1/profiles", authorized = true)

        assertEquals("minted-access", store.session?.accessToken)
        assertEquals("Renamed In Settings", store.session?.displayName)
    }

    @Test
    fun `a refresh landing after sign-out does not resurrect the session`() = runBlocking {
        // The player signs out while the refresh is on the wire. The rotated tokens the
        // endpoint answers with belong to a session nobody holds any more; writing them
        // into the cleared store would resurrect tokens /logout just revoked.
        store.session = session(access = "expired-access", expiresInMs = -1_000L)
        server.enqueue(tokenResponse(access = "minted-access", refresh = "minted-refresh"))
        // The mock answers instantly, so the interleave is staged in the store instead: the
        // path reads current() twice before the network (validAccessToken's expiry check,
        // then refreshSession's re-check under the lock) and once after, for the guard.
        // Clearing after the second read is "signed out while the refresh was on the wire".
        store.clearAfterReads = 2

        val thrown = assertThrows(SupabaseClient.SupabaseException::class.java) {
            runBlocking { client.request("GET", "/rest/v1/profiles", authorized = true) }
        }

        // The rotated tokens are dropped, the store stays signed out, and the request
        // reports the player signed out rather than proceeding on tokens just revoked.
        assertEquals("session_missing", thrown.errorCode)
        assertNull(store.session)
    }

    // ---- fixtures --------------------------------------------------------------------

    private fun session(
        access: String = "stale-access",
        refresh: String = "good-refresh",
        expiresInMs: Long = 3_600_000L,
    ) = Session(
        accessToken = access,
        refreshToken = refresh,
        expiresAtMs = System.currentTimeMillis() + expiresInMs,
        userId = "user-1",
        email = "player@example.com",
        provider = "email",
    )

    private fun unauthorized(): MockResponse = MockResponse()
        .setResponseCode(401)
        .setBody("""{"error_code":"bad_jwt","msg":"invalid JWT"}""")

    private fun tokenResponse(access: String, refresh: String): MockResponse = MockResponse()
        .setBody(
            """
            {
              "access_token": "$access",
              "refresh_token": "$refresh",
              "expires_in": 3600,
              "user": {
                "id": "user-1",
                "email": "player@example.com",
                "app_metadata": { "provider": "email" }
              }
            }
            """.trimIndent(),
        )
}

/** The whole point of [SessionStore] being an interface. */
private class InMemorySessionStore : SessionStore {
    var session: Session? = null

    /**
     * When set, the store empties itself after this many [current] reads — the in-memory
     * stand-in for a sign-out landing while a refresh is on the wire.
     */
    var clearAfterReads: Int? = null
    private var reads = 0

    override suspend fun current(): Session? {
        val answer = session
        reads++
        clearAfterReads?.let { if (reads >= it) session = null }
        return answer
    }

    override suspend fun save(session: Session) {
        this.session = session
    }

    override suspend fun saveDisplayName(name: String) {
        session = session?.copy(displayName = name)
    }

    override suspend fun clear() {
        session = null
    }
}
