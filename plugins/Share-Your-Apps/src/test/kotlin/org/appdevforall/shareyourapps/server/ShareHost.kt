package org.appdevforall.localstore.server

import java.io.File
import java.security.KeyPair
import java.time.Instant

/**
 * The owner's side of sharing a store (design, Appendix E.2–E.3): one share at a time, for one
 * phone. The server's main thread answers the seller's requests through it, and the owner's screen
 * reads and decides through it, so its methods are synchronized — the only lock besides the log's,
 * held only for a moment each time.
 *
 * A share opens with a new code, good for [CODE_LIFETIME_MS]. The seller's server asks with the
 * code, its phone's name and a commitment to its key; then reveals the key. The owner sees the name
 * and the check code, and allows — choosing the copy's expiry — or denies. An allowed copy can be
 * downloaded until one download completes; then the share is done.
 */
class ShareHost(
    val storeDir: File,
    val storeName: String,
    private val storeKey: KeyPair,
    private val now: () -> Long = System::currentTimeMillis,
) {
    enum class State { OPEN, ASKED, REVEALED, ALLOWED, DENIED, DONE, CLOSED }

    /** Why a request was refused; the sharing address turns each into an HTTP status. */
    enum class Refusal(val status: Int, val message: String) {
        WRONG_CODE(404, "That code isn't being shown here."),
        EXPIRED(410, "That code has expired. Ask the owner for a new one."),
        TAKEN(409, "Another phone is already using that code."),
        BAD_KEY(403, "This phone's key didn't match what it promised."),
        NOT_ALLOWED(403, "The owner hasn't allowed this copy."),
        SELLER_OLDER(426, "This phone's Local Store Server is older than the owner's. Get the newer one from the owner's share screen — the small QR code — then try again."),
        SELLER_NEWER(426, "The owner's Local Store Server is older than this phone's. The owner needs to update it first."),
        UNKNOWN(404, "That request isn't known here."),
    }

    class Refused(val refusal: Refusal) : Exception(refusal.message)

    /** What the owner's screen shows while deciding: the phone's name and the check code. */
    class Pending(val phoneName: String, val checkCode: String)

    /** An allowed copy, ready to stream: its session key and signed description. */
    class Copy(val key: ByteArray, val description: SharePackage.Description, val storeKey: KeyPair, val storeDir: File)

    /** The server's own installed APK, offered at /share/app to a phone that doesn't have the app yet; null offers nothing. */
    @Volatile var appApk: File? = null

    val code: String = ShareCodes.normalize(ShareCodes.newCode())!!
    val displayCode: String get() = ShareCodes.display(code)
    private val openedAt = now()
    val expiresAt: Long = openedAt + CODE_LIFETIME_MS

    private var state = State.OPEN
    private var wrongGuesses = 0
    private var requestId: String? = null
    private var phoneName = ""
    private var commitment = ByteArray(0)
    private var ownerKey: KeyPair? = null
    private var checkCode: String? = null
    private var sessionKey: ByteArray? = null
    private var copyExpires: Instant? = null

    @Synchronized fun state(): State = if (state in LIVE && now() >= expiresAt) State.CLOSED.also { state = it } else state

    /** Whether the sharing address should answer at all. */
    @Synchronized fun isOpen(): Boolean = state() in LIVE || state == State.ALLOWED

    /** Step 1: the code, the phone's name and a commitment. Returns the request's id and the owner's one-time public key. */
    @Synchronized fun request(codeInput: String, name: String, commitment: ByteArray, protocol: Int = PROTOCOL): Pair<String, ByteArray> {
        if (state() == State.CLOSED && now() >= expiresAt) throw Refused(Refusal.EXPIRED)
        if (!isOpen()) throw Refused(Refusal.WRONG_CODE)
        if (ShareCodes.normalize(codeInput) != code) {
            if (++wrongGuesses >= MAX_WRONG_GUESSES) state = State.CLOSED   // guessing can't go on
            throw Refused(Refusal.WRONG_CODE)
        }
        // After the code, so strangers can't probe which version a phone runs.
        if (protocol < PROTOCOL) throw Refused(Refusal.SELLER_OLDER)
        if (protocol > PROTOCOL) throw Refused(Refusal.SELLER_NEWER)
        if (state != State.OPEN) throw Refused(Refusal.TAKEN)
        val id = Base32.encode(ShareCrypto.randomBytes(15))
        val key = ShareCrypto.newKeyPair()
        requestId = id; phoneName = name.trim().take(40).ifEmpty { "A phone" }; this.commitment = commitment; ownerKey = key
        state = State.ASKED
        return id to key.public.encoded
    }

    /** Step 3: the seller's key, checked against its commitment. Returns the check code. */
    @Synchronized fun reveal(id: String, sellerPublic: ByteArray): String {
        if (id != requestId || state() != State.ASKED) throw Refused(Refusal.UNKNOWN)
        if (!ShareCrypto.commitmentHolds(commitment, sellerPublic)) { state = State.CLOSED; throw Refused(Refusal.BAD_KEY) }
        val owner = ownerKey!!
        val seller = try { ShareCrypto.publicKey(sellerPublic) } catch (e: Exception) { state = State.CLOSED; throw Refused(Refusal.BAD_KEY) }
        checkCode = ShareCrypto.checkCode(owner.public.encoded, sellerPublic, code)
        sessionKey = ShareCrypto.sessionKey(owner.private, seller, code, owner.public.encoded, sellerPublic)
        state = State.REVEALED
        return checkCode!!
    }

    /** For the owner's screen: who's asking, once their key is revealed. */
    @Synchronized fun pending(): Pending? = if (state() == State.REVEALED) Pending(phoneName, checkCode!!) else null

    @Synchronized fun allow(expires: Instant?) { if (state() == State.REVEALED) { copyExpires = expires; state = State.ALLOWED } }
    @Synchronized fun deny() { if (state() in LIVE) state = State.DENIED }
    @Synchronized fun close() { if (state != State.DONE) state = State.CLOSED }

    /** What the seller's server hears when it asks: waiting, allowed, denied or expired. */
    @Synchronized fun status(id: String): String {
        if (id != requestId) throw Refused(Refusal.UNKNOWN)
        return when (state()) {
            State.ASKED, State.REVEALED -> "waiting"
            State.ALLOWED, State.DONE -> "allowed"
            State.DENIED -> "denied"
            else -> "expired"
        }
    }

    @Synchronized fun copyExpiry(): Instant? = copyExpires

    /** The copy to stream, only once allowed. */
    @Synchronized fun copyFor(id: String): Copy {
        if (id != requestId) throw Refused(Refusal.UNKNOWN)
        if (state() != State.ALLOWED) throw Refused(Refusal.NOT_ALLOWED)
        val description = SharePackage.describe(storeDir, storeName, storeKey, Instant.ofEpochMilli(now()), copyExpires)
        return Copy(sessionKey!!, description, storeKey, storeDir)
    }

    private var failures = 0
    /** A transfer of the allowed copy failed partway; the seller may try again while the share is open. */
    @Synchronized fun transferFailed() { failures++ }
    @Synchronized fun failures(): Int = failures

    /** One download finished: the share is done, and the code can't be used again. */
    @Synchronized fun sent() { if (state == State.ALLOWED) state = State.DONE }

    @Synchronized fun phoneName(): String = phoneName

    companion object {
        const val CODE_LIFETIME_MS = 10 * 60 * 1000L
        const val MAX_WRONG_GUESSES = 5
        /** The sharing protocol's version; both phones must match. Raise it with any change to the exchange or the copy's format. */
        const val PROTOCOL = 1
        private val LIVE = setOf(State.OPEN, State.ASKED, State.REVEALED)
    }
}
