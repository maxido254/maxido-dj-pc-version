package com.example.djapp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.telephony.TelephonyManager
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Currency
import java.util.Locale

/**
 * MAXIDO DJ PRO.
 *
 * How it works (see billing-server/README.md for the server side):
 *  - This phone has its own signing key inside Android Keystore. Its id (SHA-256 of the public key) is the "device id".
 *  - The server decides the price and checks every payment with M-Pesa / Flutterwave itself. The phone is never trusted
 *    to say "I paid".
 *  - After a verified payment the server signs a short-lived token ("lease") that names THIS device id and the unlocked
 *    features. The app checks that signature with the server's public key (below). A token copied to another phone is
 *    useless because the device id inside it does not match.
 *  - The lease is renewed every time the app starts online; offline it keeps working until the lease ends.
 *
 * Be honest about the limit: this stops sharing, forging and clock tricks. Nothing inside an APK can stop someone
 * who rewrites the app itself, so the checks sit in several places (see Pro.has callers) and the server records everything.
 */
object Pro {
    const val VIDEO = "video"
    const val STEMS = "stems"

    // ======== SET THESE TWO after deploying billing-server (billing-server/README.md, step 3) ========
    /** https address of your billing server, no trailing slash. */
    private const val SERVER_URL = "https://recoil-flashback-battalion.ngrok-free.dev"
    /** The public key printed by `npm run keys` (Base64). Tokens are only trusted if signed by the matching private key. */
    private const val SERVER_PUBLIC_KEY = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEoeL5rF4lSreNrjT4331Tk55SXUjAB6GbJWDKxgG1vswNW4EttezSKUDhG15ISsb+P1gmWa+2YniwvhAdlWmfEg=="
    /** false = NO server. Customers pay on your Paystack page, you check the payment in your Paystack dashboard and send them
     *  an activation code made on your PC (billing-server/make-code.bat). The app checks the code by itself, even offline.
     *  true = automatic unlocking through your billing server (billing-server/). */
    private const val USE_SERVER = false
    /** With no server, true = the customer pays on your Paystack page, types the payment reference from their receipt, and PRO switches on
     *  at once. You check the payments in Paystack later (billing-server/reconcile.bat). Nobody can verify a payment inside the phone, so
     *  this relies on honesty: a fake reference also unlocks. false = activation codes only (billing-server/make-code.bat). */
    private const val TRUST_CLAIMS = false
    /** Your Paystack payment page. Use a LIVE, one-time page for real sales (a test page only takes fake payments). */
    private const val PAYMENT_PAGE_URL = "https://paystack.shop/pay/rkn3g6y07t"
    /** Optional second page for customers outside Kenya (another currency). Leave "" to use the page above for everyone. */
    private const val PAYMENT_PAGE_URL_OTHER = ""
    // ==================================================================================================

    class Offer(val method: String, val amount: Double, val currency: String) {
        val label: String
            get() = if (currency == "KES") "KSh ${amount.toLong()}"
            else if (amount % 1.0 == 0.0) "${amount.toLong()} $currency"
            else String.format(Locale.US, "%.2f %s", amount, currency)
    }
    private class Grant(val features: Set<String>, val expMs: Long)
    private class Resp(val code: Int, val json: JSONObject)

    /** Compose state for the paywall and the PRO settings page. */
    var active by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf("")
    var paywallFor by mutableStateOf<String?>(null)
    val offers = mutableStateListOf<Offer>()
    var offersLoaded by mutableStateOf(false)
        private set
    /** The payment page address while a payment is in progress, so the customer can open it again. */
    var payLink by mutableStateOf("")
        private set

    @Volatile private var grant: Grant? = null
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null
    private var skewMs = 0L
    private val nudged = HashSet<String>()
    private val prefs get() = app.getSharedPreferences("maxido_pro", Context.MODE_PRIVATE)

    private const val KEY_ALIAS = "maxido_pro_device_v1"
    private val B64URL = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING

    val serverMode: Boolean get() = USE_SERVER
    val claimsEnabled: Boolean get() = TRUST_CLAIMS && !USE_SERVER
    val configured: Boolean get() = USE_SERVER && !SERVER_URL.contains("YOUR-BILLING") && !SERVER_PUBLIC_KEY.startsWith("PASTE") && SERVER_URL.startsWith("https://")

    // ------------------------------------------------------------------------------------------ public checks
    /** True when this phone holds a valid, unexpired, correctly signed token for [feature]. */
    fun has(feature: String): Boolean {
        val g = grant ?: return false
        return feature in g.features && trustedNow() < g.expMs
    }

    /** Use for something the user just tried to do: true = go ahead, false = the paywall is now showing. */
    fun gate(feature: String): Boolean {
        if (has(feature)) return true
        paywallFor = feature
        return false
    }

    /** For things that happen by themselves (loading a video song): explain once per session, never block the music. */
    fun nudge(feature: String) {
        if (!has(feature) && nudged.add(feature)) paywallFor = feature
    }

    /** Opens the paywall from a button (Settings > PRO). */
    fun show() { paywallFor = "settings" }

    fun closePaywall() { paywallFor = null; if (!busy) message = "" }

    fun init(ctx: Context) {
        app = ctx.applicationContext
        prefs.getString("token", null)?.let { t -> verify(t)?.let { grant = it } }
        if (grant == null) loadClaim()
        active = grant != null && has(VIDEO)
        refreshNow()
    }

    /** Asks the server for a fresh lease (and finishes a payment that was still pending when the app was closed). */
    fun refreshNow(force: Boolean = false) {
        if (!configured) {
            if (force) message = if (has(VIDEO) || has(STEMS)) "PRO is on for this phone." else if (!USE_SERVER) "Enter your activation code to switch PRO on." else "Payments are not switched on in this build yet."
            return
        }
        if (force) {
            scope.launch {
                busy = true; message = "Checking…"
                try {
                    val s = fetchEntitlement()
                    message = when (s) {
                        "ok" -> "PRO is on for this phone."
                        "not_paid" -> "No payment found for this phone yet."
                        "network" -> "No connection to the server. Check your internet."
                        "moved" -> "PRO was moved to another phone."
                        else -> "Status: $s"
                    }
                } finally { busy = false }
            }
            return
        }
        scope.launch {
            val pending = prefs.getString("pending", null)
            if (pending != null) checkPending(pending)
            if (registeredOnce() || pending != null || grant != null) fetchEntitlement()
        }
    }

    // ------------------------------------------------------------------------------------------ price + payment
    fun loadQuote(ctx: Context) {
        if (!configured) return
        scope.launch {
            val cc = country(ctx)
            val r = http("GET", "/v1/quote?cc=$cc&cur=${currencyFor(cc)}", null, emptyMap())
            if (r.code != 200) return@launch
            val arr = r.json.optJSONArray("options") ?: return@launch
            val list = ArrayList<Offer>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(Offer(o.getString("method"), o.getDouble("amount"), o.getString("currency")))
            }
            withContext(Dispatchers.Main) { offers.clear(); offers.addAll(list); offersLoaded = true }
        }
    }

    /** Opens your Paystack payment page in the browser. */
    fun openPaymentPage(ctx: Context) {
        val other = PAYMENT_PAGE_URL_OTHER
        val url = if (other.isNotEmpty() && country(ctx) != "KE") other else PAYMENT_PAGE_URL
        if (url.startsWith("https://")) openLink(ctx, url) else message = "The payment page is not set up in this build."
    }

    /** "I have paid": remembers the payment reference, signed by THIS phone's secure key (so it cannot be copied to another phone). */
    fun claim(reference: String) {
        val ref = reference.trim()
        if (!Regex("^[A-Za-z0-9_-]{8,40}$").matches(ref)) {
            message = "Type the payment reference exactly as in your Paystack receipt (letters and numbers, no spaces)."
            return
        }
        try {
            val now = System.currentTimeMillis()
            val s = Signature.getInstance("SHA256withECDSA")
            s.initSign(privateKey())
            s.update("claim|${deviceId()}|$ref|$now".toByteArray(Charsets.UTF_8))
            prefs.edit().putString("claimRef", ref).putLong("claimTs", now).putString("claimSig", Base64.encodeToString(s.sign(), Base64.NO_WRAP)).apply()
            loadClaim()
            message = if (active) "PRO is on. Thank you!" else "Could not switch PRO on. Try again."
        } catch (_: Exception) {
            message = "This phone could not create its secure key."
        }
    }

    private fun loadClaim() {
        try {
            val ref = prefs.getString("claimRef", null) ?: return
            val ts = prefs.getLong("claimTs", 0L)
            val sig = Base64.decode(prefs.getString("claimSig", null) ?: return, Base64.NO_WRAP)
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (!ks.containsAlias(KEY_ALIAS)) return
            val v = Signature.getInstance("SHA256withECDSA")
            v.initVerify(ks.getCertificate(KEY_ALIAS).publicKey)
            v.update("claim|${deviceId()}|$ref|$ts".toByteArray(Charsets.UTF_8))
            if (v.verify(sig)) {
                grant = Grant(setOf(VIDEO, STEMS), ts + 20L * 365L * 24L * 3600L * 1000L)
                active = has(VIDEO) || has(STEMS)
            }
        } catch (_: Exception) { }
    }

    /** The payment reference this phone used to unlock PRO ("" if it did not). */
    fun claimReference(): String = try { if (grant != null) prefs.getString("claimRef", "") ?: "" else "" } catch (_: Exception) { "" }

    fun paymentDetailsMessage(): String =
        "Hi, I paid for MAXIDO DJ PRO.\nPayment reference: ${claimReference()}\nMy Phone ID: ${deviceIdFull()}"

    /** Activation code from the owner: checked here against the public key, and it only works on THIS phone. No internet needed. */
    fun redeem(code: String) {
        val t = code.filter { !it.isWhitespace() }
        val g = verify(t)
        if (g == null) {
            message = "That code does not work on this phone. Paste all of it, and make sure you sent us THIS phone's ID."
            return
        }
        prefs.edit().putString("token", t).apply()
        grant = g; active = has(VIDEO) || has(STEMS)
        message = if (active) "PRO is on. Thank you!" else "That code has expired."
    }

    /** Opens Paystack's secure website, where the customer chooses M-Pesa or card. */
    fun payOnline(ctx: Context, email: String) {
        val cc = country(ctx)
        startCheckout(ctx, JSONObject().put("method", "online").put("email", email.trim()).put("cc", cc).put("cur", currencyFor(cc)))
    }

    fun reopenLink(ctx: Context) { val l = payLink; if (l.startsWith("https://")) openLink(ctx, l) }

    fun payMpesa(ctx: Context, phone: String) =
        startCheckout(ctx, JSONObject().put("method", "mpesa").put("phone", phone.trim()))

    fun payCard(ctx: Context, email: String) {
        val cc = country(ctx)
        startCheckout(ctx, JSONObject().put("method", "card").put("email", email.trim()).put("cc", cc).put("cur", currencyFor(cc)))
    }

    fun cancelWaiting() { pollJob?.cancel(); busy = false; payLink = ""; message = "" }

    private fun startCheckout(ctx: Context, body: JSONObject) {
        if (!configured) { message = "Payments are not switched on in this build yet."; return }
        if (busy) return
        pollJob?.cancel()
        pollJob = scope.launch {
            busy = true; message = "Contacting the payment server…"
            try {
                val r = signed("POST", "/v1/checkout", body)
                when {
                    r.code == 200 -> {
                        val id = r.json.getString("orderId")
                        val link = r.json.optString("link")
                        prefs.edit().putString("pending", id).apply()
                        if (link.startsWith("https://")) {
                            payLink = link
                            withContext(Dispatchers.Main) { openLink(ctx, link) }
                            message = "Finish the payment in your browser, then come back. PRO switches on by itself."
                            pollOrder(id, 100)
                        } else {
                            message = "Check your phone and enter your M-Pesa PIN on the prompt."
                            pollOrder(id, 40)
                        }
                    }
                    r.code == 409 -> { message = "This phone already has PRO."; fetchEntitlement() }
                    else -> message = errorText(r)
                }
            } finally { busy = false; payLink = "" }
        }
    }

    private suspend fun pollOrder(id: String, tries: Int) {
        repeat(tries) {
            delay(3000)
            val r = signed("GET", "/v1/order/$id", null)
            if (r.code == 200) {
                when (r.json.optString("status")) {
                    "paid" -> {
                        prefs.edit().remove("pending").putString("paidOrder", id).apply()
                        message = "Payment received. Unlocking…"
                        val s = fetchEntitlement()
                        message = if (s == "ok") "PRO is on. Thank you!" else "Paid, but could not unlock yet ($s). Open the app again with internet."
                        return
                    }
                    "failed", "expired" -> {
                        prefs.edit().remove("pending").apply()
                        message = "The payment did not go through. You were not charged for PRO. Try again."
                        return
                    }
                }
            }
        }
        message = "Still waiting. If you paid, PRO will switch on the next time you open the app online, or use RESTORE."
    }

    private suspend fun checkPending(id: String) {
        val r = signed("GET", "/v1/order/$id", null)
        if (r.code == 200) {
            val st = r.json.optString("status")
            if (st == "failed" || st == "expired" || st == "revoked") prefs.edit().remove("pending").apply()
            if (st == "paid") prefs.edit().remove("pending").apply()
        } else if (r.code == 404) prefs.edit().remove("pending").apply()
    }

    /** New phone / reinstall: the M-Pesa receipt code (from the SMS) plus the phone number (or e-mail for card) used to pay. */
    fun restore(receipt: String, proof: String) {
        if (!configured) { message = "Payments are not switched on in this build yet."; return }
        if (busy) return
        scope.launch {
            busy = true; message = "Checking your payment…"
            try {
                val r = signed("POST", "/v1/restore", JSONObject().put("receipt", receipt.trim()).put("proof", proof.trim()))
                message = when (r.code) {
                    200 -> { val s = fetchEntitlement(); if (s == "ok") "PRO restored on this phone." else "Restored, but could not unlock yet ($s)." }
                    404 -> "That receipt and phone number / e-mail do not match a payment."
                    403 -> "This payment has already been moved to the most phones allowed. Contact support on WhatsApp."
                    else -> errorText(r)
                }
            } finally { busy = false }
        }
    }

    // ------------------------------------------------------------------------------------------ entitlement
    /** "ok" when a valid token was stored; otherwise the reason (not_paid, revoked, moved, expired, network...). */
    private fun fetchEntitlement(): String {
        val r = signed("POST", "/v1/entitlement", JSONObject())
        return when (r.code) {
            200 -> {
                val t = r.json.optString("token")
                val g = verify(t)
                if (g == null) "bad_token" else {
                    onServerTime(r.json.optLong("serverTime"))
                    prefs.edit().putString("token", t).apply()
                    grant = g; active = has(VIDEO) || has(STEMS)
                    "ok"
                }
            }
            402 -> {
                prefs.edit().remove("token").apply()
                grant = null; active = false
                r.json.optString("error", "not_paid")
            }
            -1 -> "network"
            else -> r.json.optString("error", "error")
        }
    }

    /** Checks the server's signature, that the token names THIS device, and reads the unlocked features and expiry. */
    private fun verify(token: String): Grant? = try {
        val dot = token.indexOf('.')
        if (dot <= 0) null else {
            val payload = token.substring(0, dot)
            val sig = Base64.decode(token.substring(dot + 1), B64URL)
            val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.decode(SERVER_PUBLIC_KEY, Base64.DEFAULT)))
            val v = Signature.getInstance("SHA256withECDSA")
            v.initVerify(pub)
            v.update(payload.toByteArray(Charsets.US_ASCII))
            if (!v.verify(sig)) null else {
                val j = JSONObject(String(Base64.decode(payload, B64URL), Charsets.UTF_8))
                if (j.optInt("v") != 1 || j.optString("did") != deviceId()) null else {
                    val arr = j.getJSONArray("feat")
                    val set = HashSet<String>()
                    for (i in 0 until arr.length()) set.add(arr.getString(i))
                    Grant(set, j.getLong("exp") * 1000L)
                }
            }
        }
    } catch (_: Exception) { null }

    // ------------------------------------------------------------------------------------------ clock
    /** Never earlier than the latest time this phone has ever shown: setting the clock back does not extend a lease. */
    private fun trustedNow(): Long {
        val wall = System.currentTimeMillis()
        val seen = try { prefs.getLong("maxWall", 0L) } catch (_: Exception) { 0L }
        if (wall > seen) { try { prefs.edit().putLong("maxWall", wall).apply() } catch (_: Exception) {} }
        return maxOf(wall, seen)
    }
    /** The server's clock is the one authority that may also move our "latest time" backwards (fixes a wrongly set-forward phone). */
    private fun onServerTime(t: Long) {
        if (t <= 0L) return
        skewMs = t - System.currentTimeMillis()
        prefs.edit().putLong("maxWall", t).apply()
    }

    // ------------------------------------------------------------------------------------------ device key (Android Keystore)
    private fun privateKey(): PrivateKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(KEY_ALIAS)) {
            val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            kpg.initialize(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build()
            )
            kpg.generateKeyPair()
        }
        return ks.getKey(KEY_ALIAS, null) as PrivateKey
    }
    private fun publicKeyBytes(): ByteArray {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        privateKey()                                           // creates the pair on first use
        val pub: PublicKey = ks.getCertificate(KEY_ALIAS).publicKey
        return pub.encoded                                    // X.509 SubjectPublicKeyInfo, what the server expects
    }
    fun deviceId(): String = try { hex(MessageDigest.getInstance("SHA-256").digest(publicKeyBytes())) } catch (_: Exception) { "" }
    /** The payment reference to keep: needed to restore PRO on a new phone. */
    fun paidReference(): String = try { prefs.getString("paidOrder", "") ?: "" } catch (_: Exception) { "" }

    fun deviceIdFull(): String = deviceId()
    fun deviceIdShort(): String = deviceId().take(8).uppercase(Locale.US)

    // ------------------------------------------------------------------------------------------ signed requests
    private fun registeredOnce() = prefs.getBoolean("registered", false)

    private fun register(): Boolean {
        val body = JSONObject().put("pub", Base64.encodeToString(publicKeyBytes(), Base64.NO_WRAP)).put("cc", country(app))
        val r = signedOnce("POST", "/v1/register", body)
        if (r.code == 401 && r.json.optString("error") == "clock_skew") {
            skewMs = r.json.optLong("serverTime") - System.currentTimeMillis()
            val r2 = signedOnce("POST", "/v1/register", body)
            if (r2.code == 200) prefs.edit().putBoolean("registered", true).apply()
            return r2.code == 200
        }
        if (r.code == 200) prefs.edit().putBoolean("registered", true).apply()
        return r.code == 200
    }

    private fun signed(method: String, path: String, body: JSONObject?): Resp {
        if (!registeredOnce() && !register()) return Resp(-1, JSONObject().put("error", "network"))
        var r = signedOnce(method, path, body)
        if (r.code == 401) {
            when (r.json.optString("error")) {
                "clock_skew" -> { skewMs = r.json.optLong("serverTime") - System.currentTimeMillis(); r = signedOnce(method, path, body) }
                "unknown_device" -> { prefs.edit().putBoolean("registered", false).apply(); if (register()) r = signedOnce(method, path, body) }
            }
        }
        return r
    }

    private fun signedOnce(method: String, path: String, body: JSONObject?): Resp {
        return try {
            val raw = body?.toString() ?: ""
            val ts = System.currentTimeMillis() + skewMs
            val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }.let { hex(it) }
            val msg = "$method\n$path\n$ts\n$nonce\n" + hex(MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8)))
            val s = Signature.getInstance("SHA256withECDSA")
            s.initSign(privateKey())
            s.update(msg.toByteArray(Charsets.UTF_8))
            val sig = Base64.encodeToString(s.sign(), Base64.NO_WRAP)
            http(method, path, if (body == null) null else raw, mapOf("X-Device" to deviceId(), "X-Ts" to ts.toString(), "X-Nonce" to nonce, "X-Sig" to sig))
        } catch (e: Exception) {
            Resp(-1, JSONObject().put("error", "key_error"))
        }
    }

    private fun http(method: String, path: String, body: String?, headers: Map<String, String>): Resp {
        var c: HttpURLConnection? = null
        return try {
            c = (URL(SERVER_URL + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 15_000; readTimeout = 20_000
                instanceFollowRedirects = false
                for ((k, v) in headers) setRequestProperty(k, v)
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
            }
            val code = c.responseCode
            val stream = if (code >= 400) c.errorStream else c.inputStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: "{}"
            Resp(code, try { JSONObject(text) } catch (_: Exception) { JSONObject() })
        } catch (e: Exception) {
            Resp(-1, JSONObject().put("error", "network"))
        } finally { c?.disconnect() }
    }

    // ------------------------------------------------------------------------------------------ helpers
    private fun errorText(r: Resp): String = when (r.json.optString("error")) {
        "network" -> "No connection to the payment server. Check your internet and try again."
        "key_error" -> "This phone could not create its secure key."
        "bad_phone" -> "Enter a Kenyan number, like 0712 345 678."
        "bad_email" -> "Enter a valid e-mail address."
        "rate_limited", "too_many_orders" -> "Too many tries. Wait a few minutes and try again."
        "provider_error" -> "The payment service is not answering. Try again in a moment."
        "method_unavailable" -> "That way of paying is not available right now."
        "clock_skew" -> "Your phone's clock is wrong. Set the date and time to automatic."
        else -> "Something went wrong (${r.code}). Try again."
    }

    private fun country(ctx: Context): String {
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val c = tm?.simCountryIso?.takeIf { it.length == 2 } ?: tm?.networkCountryIso?.takeIf { it.length == 2 } ?: Locale.getDefault().country
        return c.uppercase(Locale.US).ifEmpty { "KE" }
    }
    private fun currencyFor(cc: String): String = try { Currency.getInstance(Locale("", cc)).currencyCode } catch (_: Exception) { "USD" }

    private fun openLink(ctx: Context, url: String) {
        try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: Exception) { message = "Could not open the browser." }
    }

    private fun hex(b: ByteArray): String { val sb = StringBuilder(b.size * 2); for (x in b) sb.append(String.format("%02x", x)); return sb.toString() }
}
