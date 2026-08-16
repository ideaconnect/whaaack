package tech.idct.whaaack.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What `updateDisplayName` does with the answer, against a real socket.
 *
 * The rename path has now produced a *ghost success* — a change reported as done over a server
 * that changed nothing — through three separate doors: no session at all, an anon-key fallback
 * during a token-endpoint outage, and this one, a PATCH addressing a `profiles` row that does
 * not exist. The first two are pinned by `SupabaseClientTest` and `AuthErrorTest`. This is the
 * third, and it is the one that no amount of reading the Kotlin catches, because the failure is
 * entirely in what PostgREST *doesn't* say: a PATCH matching no rows is `200 []`, not an error,
 * so every layer above it is right to think the request succeeded.
 */
class RenameTest {

    private lateinit var server: MockWebServer
    private lateinit var store: RecordingSessionStore
    private lateinit var auth: AuthRepository

    @Before
    fun setUp() = runBlocking {
        server = MockWebServer()
        server.start()
        store = RecordingSessionStore()
        store.session = Session(
            accessToken = "access",
            refreshToken = "refresh",
            expiresAtMs = System.currentTimeMillis() + 3_600_000L,
            userId = "user-1",
            email = "player@example.com",
            provider = "email",
            displayName = "Zenek",
        )
        auth = AuthRepository(
            SupabaseClient(
                baseUrl = server.url("/").toString().trimEnd('/'),
                anonKey = "anon-key",
                sessions = store,
            ),
        )
        // Publishes the session-derived player, so the assertions below can watch it move.
        auth.restore()
        Unit
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `a patch that matched no rows is a failure, not a rename`() {
        // The bug in one line: this is what PostgREST answers when the account has no profiles
        // row — 200, no error, and an empty array where the updated row should be.
        server.enqueue(MockResponse().setBody("[]"))

        val thrown = assertThrows(AuthResultException::class.java) {
            runBlocking { auth.updateDisplayName("Nowak") }
        }

        assertEquals(AuthError.ProfileMissing, thrown.error)
        // And nothing anywhere may have started believing the new name.
        assertEquals("Zenek", auth.player.value?.displayName)
        assertEquals("Zenek", store.session?.displayName)
    }

    @Test
    fun `a patch that returned the row adopts the name the server stored`() = runBlocking {
        server.enqueue(MockResponse().setBody("""[{"display_name":"Nowak","provider":"email"}]"""))

        auth.updateDisplayName("  Nowak  ")

        assertEquals("Nowak", auth.player.value?.displayName)
        // Cached too, or the next cold start names the player with the old one until the
        // profile request lands.
        assertEquals("Nowak", store.session?.displayName)
        // Trimmed on the way out, and sent to this player's row only.
        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertTrue(request.path!!.contains("id=eq.user-1"))
        assertEquals("""{"display_name":"Nowak"}""", request.body.readUtf8())
        assertEquals("return=representation", request.getHeader("Prefer"))
    }

    @Test
    fun `a body that is not an array is not read as an empty one`() = runBlocking {
        // A 204 from a PostgREST that ignored Prefer, or a proxy rewriting the response.
        // Ambiguous, and the only safe reading is the optimistic one: calling this
        // ProfileMissing would report a rename that worked as a failure, and send the player
        // round again to take a name they now hold themselves.
        server.enqueue(MockResponse().setResponseCode(204))

        auth.updateDisplayName("Nowak")

        assertEquals("Nowak", auth.player.value?.displayName)
    }

    @Test
    fun `a name somebody already holds comes back as taken, never as a number`() {
        // Renaming is the one path that must *not* auto-number: the player asked for this
        // exact name. Numbering belongs to signup, where nobody is at the keyboard to be asked.
        server.enqueue(
            MockResponse()
                .setResponseCode(409)
                .setBody(
                    """{"code":"23505","message":"duplicate key value violates unique """ +
                        """constraint \"profiles_display_name_key\""}""",
                ),
        )

        val thrown = assertThrows(AuthResultException::class.java) {
            runBlocking { auth.updateDisplayName("Nowak") }
        }

        assertEquals(AuthError.NameTaken, thrown.error)
        assertEquals("Zenek", auth.player.value?.displayName)
    }

    @Test
    fun `a malformed name never reaches the network`() {
        // Because the answer it would come back with is a 23514 quoting display_name_shape,
        // which the error mapping has no branch for and no player can act on.
        for (bad in listOf("a", "-nowak-", "李雷", "abcdefghijklmnopqrstuvwxy")) {
            val thrown = assertThrows(AuthResultException::class.java) {
                runBlocking { auth.updateDisplayName(bad) }
            }
            assertTrue(
                "should be reported as a bad name: $bad",
                thrown.error is AuthError.NameInvalid,
            )
        }
        assertEquals("nothing should have been sent", 0, server.requestCount)
        assertEquals("Zenek", auth.player.value?.displayName)
    }
}

/** A [SessionStore] that keeps everything in memory and lets the test read it back. */
private class RecordingSessionStore : SessionStore {
    var session: Session? = null

    override suspend fun current(): Session? = session

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
