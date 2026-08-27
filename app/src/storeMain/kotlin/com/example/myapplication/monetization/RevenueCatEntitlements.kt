package com.example.myapplication.monetization

import com.revenuecat.purchases.kmp.LogLevel
import com.revenuecat.purchases.kmp.Purchases
import com.revenuecat.purchases.kmp.PurchasesConfiguration
import com.revenuecat.purchases.kmp.models.CustomerInfo
import com.revenuecat.purchases.kmp.models.Package
import com.revenuecat.purchases.kmp.models.PackageType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * RevenueCat-backed [Entitlements] for the two store platforms (§0.4). Lives in `storeMain` — the
 * intermediate source set shared by `androidMain` and `iosMain` — because `purchases-kmp-core`
 * publishes no desktop/JVM or wasm artifact. Desktop and wasm use [NoOpEntitlements] instead.
 *
 * Construct via [createOrNull] from the platform entry point and pass the result into `AppRoot`,
 * exactly like `PgnSharer` / `Board3DSupport`. It is deliberately **not** the `AppRoot` default:
 * that stays [UnconfiguredEntitlements] so previews, Compose UI tests, and any caller that omits
 * the argument never configure a billing SDK or hit the network.
 *
 * No `Context` is needed on Android — the AAR ships an `androidx.startup` `PurchasesApplicationProvider`
 * that captures the Application context before `onCreate` runs.
 */
class RevenueCatEntitlements private constructor(
    private val entitlementId: String,
    /**
     * Whether this instance was configured with a `test_…` Test Store key, which only a debug build
     * ever resolves (see `revenueCatApiKey`). Kept solely so [restorePurchases] can explain an empty
     * restore instead of blaming a store account that doesn't exist on that key.
     */
    private val isTestStore: Boolean,
) : Entitlements {

    private val _isProUnlocked = MutableStateFlow(false)
    override val isProUnlocked: StateFlow<Boolean> = _isProUnlocked.asStateFlow()

    /** Cached so [purchase] can resolve a plan id back to the SDK `Package` the user actually saw. */
    private var packagesById: Map<String, Package> = emptyMap()

    /**
     * Fetch the cached customer info and publish the entitlement. Suspending and explicit rather
     * than fired from `init {}`: the entry point decides when this runs, so it never executes on
     * the composition thread as a side effect of `remember { … }`.
     */
    suspend fun refresh() {
        val unlocked = suspendCoroutine { continuation ->
            Purchases.sharedInstance.getCustomerInfo(
                onSuccess = { continuation.resume(it.hasPro()) },
                // A lookup failure is not a purchase signal — keep the previous value rather than
                // revoking access on a transient network error.
                onError = { continuation.resume(_isProUnlocked.value) },
            )
        }
        _isProUnlocked.value = unlocked
    }

    override suspend fun availablePlans(): List<ProPlan> {
        val packages = suspendCoroutine<List<Package>> { continuation ->
            Purchases.sharedInstance.getOfferings(
                onSuccess = { continuation.resume(it.current?.availablePackages.orEmpty()) },
                onError = { continuation.resume(emptyList()) },
            )
        }
        packagesById = packages.associateBy { it.identifier }

        // Longer commitments first: annual/lifetime are the better per-period value and the ones
        // worth emphasizing. Anything unrecognized sorts last rather than being dropped.
        val ranked = packages.sortedBy { PLAN_ORDER.indexOf(it.packageType).takeIf { i -> i >= 0 } ?: PLAN_ORDER.size }
        val bestValueId = ranked.firstOrNull { it.packageType in BEST_VALUE_TYPES }?.identifier

        return ranked.map { pkg ->
            val product = pkg.storeProduct
            ProPlan(
                id = pkg.identifier,
                title = product.title.ifBlank { pkg.packageType.name.lowercase().replaceFirstChar(Char::uppercase) },
                // The store's own localized string — never format a price from amountMicros.
                priceLabel = product.price.formatted,
                detail = product.introductoryDiscount?.let { "Includes a free trial" },
                isBestValue = pkg.identifier == bestValueId && ranked.size > 1,
            )
        }
    }

    override suspend fun purchase(planId: String): PurchaseOutcome {
        // availablePlans() may not have run yet (deep link straight to the paywall), so resolve
        // lazily rather than failing.
        val target = packagesById[planId]
            ?: run { availablePlans(); packagesById[planId] }
            ?: return PurchaseOutcome.Unavailable

        val outcome = suspendCoroutine<PurchaseOutcome> { continuation ->
            Purchases.sharedInstance.purchase(
                packageToPurchase = target,
                onSuccess = { _, customerInfo ->
                    continuation.resume(
                        if (customerInfo.hasPro()) PurchaseOutcome.Purchased
                        // Paid, but the entitlement isn't attached to this product in the
                        // dashboard. Reporting success would unlock nothing and look like a bug.
                        else PurchaseOutcome.Failed("Purchase completed but Pro is not active."),
                    )
                },
                // The second parameter is userCancelled — backing out of the store sheet is a
                // correct user action and must not surface as an error.
                onError = { error, userCancelled ->
                    continuation.resume(
                        if (userCancelled) PurchaseOutcome.Cancelled
                        else PurchaseOutcome.Failed(error.message),
                    )
                },
            )
        }
        if (outcome is PurchaseOutcome.Purchased) _isProUnlocked.value = true
        return outcome
    }

    /**
     * Restores from the **store account**, which is the only place a purchase survives an uninstall:
     * the SDK re-reads the App Store / Play account's transactions and syncs them onto this install's
     * App User ID. Nothing is configured with a stable app user id here — [Purchases.configure] takes
     * no `appUserID` — so every install is a fresh anonymous `$RCAnonymousID:…` and the store account
     * is the *only* link back to a previous one.
     *
     * **A RevenueCat Test Store (`test_…`) purchase therefore cannot be restored after a reinstall,
     * and that is not a bug here.** The Test Store has no Apple/Google account behind it: the
     * transaction exists only against the anonymous id that install generated, and uninstalling
     * erases it. Validate restore against the Play/App Store sandbox instead — see
     * https://www.revenuecat.com/docs/getting-started/restoring-purchases.
     *
     * An error is reported as [RestoreOutcome.Failed] and leaves [isProUnlocked] alone, for the same
     * reason [refresh] does: a failed lookup is not a signal that the user lost their subscription.
     * Only a successful store answer moves the flag.
     */
    override suspend fun restorePurchases(): RestoreOutcome {
        val outcome = suspendCoroutine<RestoreOutcome> { continuation ->
            Purchases.sharedInstance.restorePurchases(
                onSuccess = { customerInfo ->
                    continuation.resume(
                        when {
                            customerInfo.hasPro() -> RestoreOutcome.Restored
                            // Not NothingToRestore: on the Test Store the empty answer is a property
                            // of the *store*, not of the account. Saying "this account has no
                            // purchase" would blame a Play/App Store account that isn't involved.
                            isTestStore -> RestoreOutcome.Failed(
                                "Test Store purchases can't be restored — they belong to this " +
                                    "install, not to a store account. Use the Play/App Store " +
                                    "sandbox to test restore.",
                            )
                            else -> RestoreOutcome.NothingToRestore
                        },
                    )
                },
                onError = { error -> continuation.resume(RestoreOutcome.Failed(error.message)) },
            )
        }
        when (outcome) {
            RestoreOutcome.Restored -> _isProUnlocked.value = true
            RestoreOutcome.NothingToRestore -> _isProUnlocked.value = false
            RestoreOutcome.Unavailable, is RestoreOutcome.Failed -> Unit
        }
        return outcome
    }

    private fun CustomerInfo.hasPro(): Boolean = entitlements.active.containsKey(entitlementId)

    companion object {
        /** The entitlement identifier configured in the RevenueCat dashboard. */
        const val DEFAULT_ENTITLEMENT_ID = "pro"

        /** RevenueCat's prefix for a Test Store key; production keys are `goog_` / `appl_`. */
        private const val TEST_STORE_KEY_PREFIX = "test_"

        private val PLAN_ORDER = listOf(
            PackageType.LIFETIME,
            PackageType.ANNUAL,
            PackageType.SIX_MONTH,
            PackageType.THREE_MONTH,
            PackageType.TWO_MONTH,
            PackageType.MONTHLY,
            PackageType.WEEKLY,
        )
        private val BEST_VALUE_TYPES = setOf(PackageType.LIFETIME, PackageType.ANNUAL)

        /**
         * Configure the SDK and return an instance, or `null` when [apiKey] is blank — which is the
         * state of any clone without `revenuecat.androidKey` / `revenuecat.iosKey` in
         * `local.properties` (or the matching env vars). Callers fall back to
         * [UnconfiguredEntitlements], so an unkeyed build is locked rather than broken.
         *
         * [debugLogging] should track the build type: `MainActivity` raises the Kermit floor to
         * `Severity.Assert` on release, and an unconditional [LogLevel.DEBUG] would undo that for
         * the billing SDK's own output.
         */
        fun createOrNull(
            apiKey: String,
            debugLogging: Boolean,
            entitlementId: String = DEFAULT_ENTITLEMENT_ID,
        ): RevenueCatEntitlements? {
            if (apiKey.isBlank()) return null
            if (!Purchases.isConfigured) {
                Purchases.logLevel = if (debugLogging) LogLevel.DEBUG else LogLevel.WARN
                // No `appUserId`: every install is a fresh anonymous `$RCAnonymousID:…`, and the
                // store account is what carries a purchase across installs and devices. Setting a
                // device-derived id here would make restore device-scoped instead — see the note on
                // restorePurchases before considering it.
                Purchases.configure(PurchasesConfiguration(apiKey))
            }
            return RevenueCatEntitlements(
                entitlementId = entitlementId,
                isTestStore = apiKey.startsWith(TEST_STORE_KEY_PREFIX),
            )
        }
    }
}

/**
 * The platform's RevenueCat public SDK key, generated into `storeMain` by `generateRevenueCatConfig`.
 *
 * [debug] selects the Test Store key (`test_…`) when one is configured, so a debug build's purchase
 * flow runs against RevenueCat's test store instead of a real Play/App Store product. A release
 * build **never** resolves to the test key, whether or not one is configured — shipping a `test_`
 * key would silently give every user a free, unverifiable "purchase". A debug build with no test
 * key falls back to the production key rather than going blank, so an existing single-key setup
 * keeps working unchanged.
 */
internal expect fun revenueCatApiKey(debug: Boolean): String
