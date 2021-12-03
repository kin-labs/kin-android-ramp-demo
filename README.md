### KIN + Ramp
# Getting Started with Android and Ramp

In this basic tutorial, we will create a simple Kin-enabled Android App and learn how to add __Ramp__. The result can be found [here](https://github.com/kin-labs/kin-android-ramp-demo).

![Ramp + Android Example](https://i.imgur.com/NHGmTEm.gif)

### What is Ramp?
> [_Ramp is an easy way to let your users buy crypto directly from your dApp._](https://docs.ramp.network/)

If your app integrates KIN, this may be a barrier for users that are new to crypto. They don't have KIN to send to your apps wallet and hence can't get started. Meet __Ramp__: With Ramp users can easily buy KIN within your app.
Ramp offers a streamlined exchange flow. Users can buy KIN simply with their credit card, online banking or SEPA transfers. They handle the entire exchange. You don't have to provide your own KIN or anything.

### 1. Adding KIN + Ramp to the Project
We start out by creating a new Android Project using Kotlin and with the minApiLevel of 21 (lower will work too, but multiDex will have to be enabled).
Let's add KIN's Android SDK and also the Ramp SDK while we are at it.
```
dependencies {
    ...
    implementation "org.kin.sdk.android:base:2.0.0"
    implementation 'com.github.RampNetwork:ramp-sdk-android:1.+'
}
```
(build.gradle - app level)

To resolve the Ramp SDK you will also have to add its repository to the `build.gradle` (project level):
```
allprojects {
    repositories {
        ...
        maven { url 'https://button.passbase.com/__android' }
    }
}
```
After rebuilding the project we should be ready to connect KIN to the App

### 2. Connecting to KIN
> Before taking your app to production, you should register it to receive your own App Index and have Kin Foundation (KF) cover transaction fees. Learn more here: https://portal.kin.org/login

To quickly get started with KIN inside an Android app, KF created the [KIN-Starter-Kit for Android](https://github.com/kintegrate/kin-starter-android/blob/main/app/src/main/java/com/kin/kin/Kin.kt). We will add the `Kin.kt` code to our project:

```
package com.example.kimramp

import android.content.Context
import android.util.Log
import org.kin.sdk.base.KinAccountContext
import org.kin.sdk.base.KinEnvironment
import org.kin.sdk.base.ObservationMode
import org.kin.sdk.base.models.*
import org.kin.sdk.base.models.Invoice
import org.kin.sdk.base.models.KinBinaryMemo
import org.kin.sdk.base.network.services.AppInfoProvider
import org.kin.sdk.base.stellar.models.NetworkEnvironment
import org.kin.sdk.base.storage.KinFileStorage
import org.kin.sdk.base.tools.Base58
import org.kin.sdk.base.tools.DisposeBag
import org.kin.sdk.base.tools.Observer
import org.kin.sdk.base.tools.Optional
import kotlin.reflect.KFunction2


/**
 * Performs operations for a [KinAccount].
 * @param appContext Context object [Context] for the app
 * @param production  Boolean indicating if [NetworkEnvironment] is in production or test
 * @param appIndex App Index assigned by the Kin Foundation
 * @param appAddress Blockchain address for the app in stellarBase32Encoded format
 * @param credentialsUser User id of [AppUserCreds] sent to your webhook for authentication
 * @param credentialsPass Password of [AppUserCreds] sent to your webhook for authentication
 * @param balanceChanged Callback [balanceChanged] to notify the app of balance changes
 * @param paymentHappened  Callback [paymentHappened] to notify the app of balance changes
 */

class Kin(
    private val appContext: Context,
    private val production: Boolean,
    private val appIndex: Int,
    private val appAddress: String,
    private val credentialsUser: String,
    private val credentialsPass: String,
    private val balanceChanged: ((balance: KinBalance) -> Unit)? = null,
    private val paymentHappened: ((payments: List<KinPayment>) -> Unit)? = null
) {
    private val lifecycle = DisposeBag()

    private val environment: KinEnvironment.Agora = getEnvironment()
    private lateinit var context: KinAccountContext
    private var observerPayments: Observer<List<KinPayment>>? = null
    private var observerBalance: Observer<KinBalance>? = null

    init {
        //fetch the account and set the context
        environment.allAccountIds().then {
            //First get (or create) an account id for this device
            val accountId = if (it.count() == 0) {
                createAccount()
            } else {
                it[0].stellarBase32Encode()
            }
            //Then set the context with that single account
            context = getKinContext(accountId)
        }
    }

    init {
        //handle listeners
        balanceChanged?.let {
            watchBalance() //watch for changes in balance
        }

        paymentHappened?.let {
            watchPayments() //watch for changes in balance
        }
    }


    /**
     * Return the device's blockchain address
     */
    fun address(): String = context.accountId.base58Encode()

    /**
     * Force the balance and payment listeners to refresh, to get transactions not initiated by this device
     */
    fun checkTransactions() {
        observerBalance?.requestInvalidation()
        observerPayments?.requestInvalidation()
    }

    /**
     * Sends Kin to the designated address
     * @param paymentItems List of items and costs in a single transaction.
     * @param address  Destination address
     * @param paymentType [KinBinaryMemo.TransferType] of Earn, Spend or P2P (for record keeping)
     * @param paymentSucceeded callback to indicate completion or failure of a payment
     */
    fun sendKin(
        paymentItems: List<Pair<String, Double>>,
        address: String,
        paymentType: KinBinaryMemo.TransferType,
        paymentSucceeded: KFunction2<KinPayment?, Throwable?, Unit>? = null
    ) {
        val kinAccount: KinAccount.Id = kinAccount(address)
        val invoice = buildInvoice(paymentItems)
        val amount = invoiceTotal(paymentItems)

        context.sendKinPayment(
            KinAmount(amount),
            kinAccount,
            buildMemo(invoice, paymentType),
            Optional.of(invoice)
        )
            .then({ payment: KinPayment ->
                paymentSucceeded?.invoke(payment, null)
            }) { error: Throwable ->
                paymentSucceeded?.invoke(null, error)
            }
    }

    private fun invoiceTotal(paymentItems: List<Pair<String, Double>>): Double {
        var total = 0.0
        paymentItems.forEach {
            total += it.second
        }

        return total
    }

    private fun buildInvoice(paymentItems: List<Pair<String, Double>>): Invoice {

        val invoiceBuilder = Invoice.Builder()

        paymentItems.forEach {
            invoiceBuilder.addLineItems(
                listOf(
                    LineItem.Builder(it.first, KinAmount(it.second)).build()
                )
            )
        }

        return invoiceBuilder.build()
    }

    private fun buildMemo(
        invoice: Invoice,
        transferType: KinBinaryMemo.TransferType
    ): KinMemo {
        val memo = KinBinaryMemo.Builder(appIndex).setTranferType(transferType)
        val invoiceList = InvoiceList.Builder().addInvoice(invoice).build()

        memo.setForeignKey(invoiceList.id.invoiceHash.decode())

        return memo.build().toKinMemo()
    }

    private fun kinAccount(accountId: String): KinAccount.Id {
        //resolve between Solana and Stellar format addresses
        return try {
            KinAccount.Id(Base58.decode(accountId))//Solana format
        } catch (ex: Exception) {
            KinAccount.Id(accountId) //Stellar format
        }
    }

    private fun watchPayments() {
        observerPayments = context.observePayments(ObservationMode.Passive)
            .add { payments: List<KinPayment> ->
                paymentHappened?.invoke(payments)
            }
            .disposedBy(lifecycle)
    }

    private fun watchBalance() {
        //watch for changes to this account
        // !!! This differs from the KinStarterKit - We use active observation to receive realtime changes !!!
        observerBalance = context.observeBalance(ObservationMode.Active)
            .add { kinBalance: KinBalance ->
                balanceChanged?.invoke(kinBalance)
            }.disposedBy(lifecycle)
    }

    private fun getKinContext(accountId: String): KinAccountContext {
        return KinAccountContext.Builder(environment)
            .useExistingAccount(KinAccount.Id(accountId))
            .build()
    }

    private fun createAccount(): String {
        val kinContext = KinAccountContext.Builder(environment)
            .createNewAccount()
            .build()
        return kinContext.accountId.stellarBase32Encode()
    }

    private fun getEnvironment(): KinEnvironment.Agora {
        val storageLoc = appContext.filesDir.toString() + "/kin"

        val networkEnv: NetworkEnvironment = if (production) {
            NetworkEnvironment.MainNet
        } else {
            NetworkEnvironment.TestNet
        }

        return KinEnvironment.Agora.Builder(networkEnv)
            .setAppInfoProvider(object : AppInfoProvider {
                override val appInfo: AppInfo =
                    AppInfo(
                        AppIdx(appIndex),
                        KinAccount.Id(appAddress),
                        appContext.applicationInfo.loadLabel(appContext.packageManager).toString(),
                        R.mipmap.ic_launcher_round
                    )

                override fun getPassthroughAppUserCredentials(): AppUserCreds {
                    return AppUserCreds(
                        credentialsUser,
                        credentialsPass
                    )
                }
            })
            .setStorage(KinFileStorage.Builder(storageLoc))
            .build()
    }
}
```

`Kin.kt` provides a more simplified Kin interface, that we then use in our `MainActivity`. The app we build is in production mode, because the KIN bought on Ramp are on the production network. We set our `appIndex` to 0. When integrating KIN into your app, you should register your own `appIndex`.
Webhooks are not a part of this tutorial, so the user credentials are just set to `"demo_uid"` and `"demo_password"`.

```
class MainActivity : AppCompatActivity() {
    lateinit var kin: Kin

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        kin = Kin(
            applicationContext, //Application context
            true, //In Production mode
            0, //Your App Index
            "EK2bHUbjbbn8YzpMP2hxgrd5vysY1UaNw1VNXQJQkVfN", //Your Public Address
            "demo_uid", // !! Webhooks are not part of this tutorial, yet you should definitely use them for production
            "demo_password", // !! Webhooks are not part of this tutorial, yet you should definitely use them for production
            ::balanceChanged, //get notifications for balance changes
            ::paymentHappened //get notifications for payments
        )
    }
}
```

To update the balance and transactions we add a `balanceChanged` and `paymentHappened` callback.
```
private fun paymentHappened(payments: List<KinPayment>) {
    //...
}

private fun balanceChanged(kinBalance: KinBalance) {
    // ...
}
```
We then start checking for updated by adding `kin.checkTransactions()` to the `onCreate` method.

### 3. Adding a UI

Now lets add a simple layout and UI. To display the wallet address and the current balance. On the bottom we add a button to later launch the Ramp purchase flow.

![Screenshot of UI](https://i.imgur.com/juKc53o.png)

```
<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    xmlns:tools="http://schemas.android.com/tools"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    tools:context=".MainActivity">

    <androidx.cardview.widget.CardView
        android:id="@+id/cardView"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginStart="32dp"
        android:layout_marginEnd="32dp"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toTopOf="parent"
        app:cardElevation="32dp"
        app:cardCornerRadius="12dp">

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:orientation="vertical"
            android:padding="16dp">

            <TextView
                android:id="@+id/tv_address"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="Wallet address"
                android:textSize="20sp"
                android:textIsSelectable="true"
                app:layout_constraintBottom_toBottomOf="parent"
                app:layout_constraintLeft_toLeftOf="parent"
                app:layout_constraintRight_toRightOf="parent"
                app:layout_constraintTop_toTopOf="parent"
                app:layout_constraintVertical_bias="0.26" />

            <TextView
                android:id="@+id/tv_balance"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="Balance"
                app:layout_constraintBottom_toBottomOf="parent"
                app:layout_constraintLeft_toLeftOf="parent"
                app:layout_constraintRight_toRightOf="parent"
                app:layout_constraintTop_toBottomOf="@+id/tv_address" />

        </LinearLayout>

    </androidx.cardview.widget.CardView>

    <Button
        android:id="@+id/btn_ramp"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="Buy more Kins with Ramp"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@+id/cardView" />

</androidx.constraintlayout.widget.ConstraintLayout>
```

We now link the `TextViews` and the `Button` to our code in the `MainActivity`.

```
lateinit var textViewAddress: TextView
lateinit var textViewBalance: TextView
lateinit var buttonRamp: Button

lateinit var kin: Kin

override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)

    // ... init of kin

    textViewAddress = findViewById(R.id.tv_address)
    textViewBalance = findViewById(R.id.tv_balance)
    buttonRamp = findViewById(R.id.btn_ramp)

    kin.checkTransactions()
}
```

The wallet address should be shown in `textViewAddress`. That can be done by using `kin.address()`. So let's add:
```
textViewAddress.text = kin.address()
```

To update the current balance we earlier created the `balanceChanged(kinBalance: KinBalance)` callback. In here we can now update the text of `textViewBalance`.
```
private fun balanceChanged(kinBalance: KinBalance) {
    textViewBalance.text = "Your current balance: "+kinBalance.amount.toString()+" KIN"
}
```

### 4. Adding Ramp!

Now that KIN is setup and we show the publicKey and balance, we can finally integrate Ramp. Firstly, we initialize the RampSDK.
```
lateinit var rampSdk: RampSDK

override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)

    // ... kin initialization

    rampSdk = RampSDK()

    // ... views and more
}
```
We will place the Ramp purchase flow inside a function called `buyKinOnRamp()`. The function should also accept the `walletAddress`. That address will then be passed into the Ramp `Config` object. In the `Config` you can configure your apps name and logo which is then shown in Ramp's UI.
```
private fun buyKinOnRamp(walletAddress: String) {
    val config = Config(
        hostLogoUrl = "https://i.imgur.com/tNi1q70.png", // replace with your own logo!
        hostAppName = getString(R.string.app_name),
        userAddress = walletAddress,
        swapAsset = "SOLANA_KIN",
    )
    
    //...
}
```
Now we create the `RampCallback` object. It contains functions informing us about the results of the Ramp flow. We log the result and show a simple `Toast`.
```
val callback = object: RampCallback {
        override fun onPurchaseFailed() {
            Log.d("MainActivity", "Ramp Purchase failed")
            Toast.makeText(applicationContext, "Your Ramp purchase failed", Toast.LENGTH_SHORT).show()
        }

        override fun onPurchaseCreated(
            purchase: Purchase,
            purchaseViewToken: String,
            apiUrl: String
        ) {
            Log.d("MainActivity", "Ramp Purchase succeeded: "+purchase.cryptoAmount+" KIN")
            Toast.makeText(applicationContext, "Your Ramp Purchase succeeded", Toast.LENGTH_SHORT).show()
        }

        override fun onWidgetClose() {
            Log.d("MainActivity", "Ramp closed")
            Toast.makeText(applicationContext, "Your Ramp transaction was closed", Toast.LENGTH_SHORT).show()
        }
    }
```
Finally we can start the transaction flow:
```
rampSdk.startTransaction(this, config, callback)
```

Now we can call that function after a button press. So in the `onCreate` we register the click listener for the `buttonRamp`. Note that balance changes won't be immediate.
```
buttonRamp.setOnClickListener {
    buyKinOnRamp(kin.address())
}
```
![You did it](https://media1.giphy.com/media/vmtxnxveVUodG/giphy.gif?cid=ecf05e47pvjtabuo0jwms4yjnrqp3ivu9kmnnkvq2hbkzba5&rid=giphy.gif&ct=g)
Congratulations! We have completed our Getting Started with KIN and Ramp!

You can learn more about the Ramp and its Android integration [here](https://docs.ramp.network/mobile/android-sdk).
You can also find the complete project on [GitHub](https://github.com/kin-labs/kin-android-ramp-demo).

