package tech.idct.whaaack

import android.app.Application
import com.google.android.gms.games.PlayGamesSdk

class WhaaackApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Play Games Services v2 signs the player in by itself, and this is where that
        // starts: initialize() kicks off the automatic sign-in attempt against whichever
        // Play Games account is already on the device, so by the time anything asks
        // isAuthenticated() there is usually an answer waiting.
        //
        // Application.onCreate is the documented home for it and the only one that works
        // properly — called from an Activity it would re-run the handshake on every
        // recreation. It is cheap and does no I/O on this thread; the sign-in itself is
        // asynchronous and needs no callback here, because nothing in the app blocks on it.
        // A device without Play Games, or a player who declines, simply leaves the SDK
        // unauthenticated and every achievement call a no-op. See PlayGamesManager.
        PlayGamesSdk.initialize(this)
    }
}
