package com.example.installlock

internal object SecretDeliveryWindow {
    fun valid(expiresElapsed: Long, deliveryBoot: Int, nowElapsed: Long, currentBoot: Int): Boolean =
        deliveryBoot >= 0 && deliveryBoot == currentBoot && nowElapsed >= 0 && nowElapsed < expiresElapsed
}
