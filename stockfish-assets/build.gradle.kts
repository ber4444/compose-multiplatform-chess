plugins {
    id("com.android.asset-pack")
}

assetPack {
    packName.set("stockfish_assets")
    dynamicDelivery {
        deliveryType.set("install-time")
    }
}
