package com.example.kimramp

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import org.kin.sdk.base.models.KinBalance
import org.kin.sdk.base.models.KinPayment
import network.ramp.sdk.events.model.Purchase
import network.ramp.sdk.facade.Config
import network.ramp.sdk.facade.RampCallback
import network.ramp.sdk.facade.RampSDK

class MainActivity : AppCompatActivity() {

    lateinit var textViewAddress: TextView
    lateinit var textViewBalance: TextView
    lateinit var buttonRamp: Button

    lateinit var kin: Kin
    lateinit var rampSdk: RampSDK

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

        rampSdk = RampSDK()

        textViewAddress = findViewById(R.id.tv_address)
        textViewBalance = findViewById(R.id.tv_balance)
        buttonRamp = findViewById(R.id.btn_ramp)

        textViewAddress.text = kin.address()

        buttonRamp.setOnClickListener {
            buyKinOnRamp(kin.address())
        }

        kin.checkTransactions()
    }



    private fun paymentHappened(payments: List<KinPayment>) {
        // todo: show notification on payments
    }

    private fun balanceChanged(kinBalance: KinBalance) {
        textViewBalance.text = "${kinBalance.amount} KIN"
    }

    private fun buyKinOnRamp(walletAddress: String) {
        val config = Config(
            hostLogoUrl = "https://i.imgur.com/tNi1q70.png",
            hostAppName = getString(R.string.app_name),
            userAddress = walletAddress,
            swapAsset = "SOLANA_KIN",
        )

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
                Log.d("MainActivity", "Ramp Purchase succeeded: ${purchase.cryptoAmount} KIN")
            }

            override fun onWidgetClose() {
                Log.d("MainActivity", "Ramp closed")
                Toast.makeText(applicationContext, "Your Ramp transaction was closed", Toast.LENGTH_SHORT).show()
            }
        }

        rampSdk.startTransaction(this, config, callback)
    }
}