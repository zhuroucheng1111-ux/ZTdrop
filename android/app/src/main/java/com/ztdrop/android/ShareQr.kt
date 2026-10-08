package com.ztdrop.android

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Actual URL QR, including a four-module quiet zone. Pure JVM code for round-trip checks. */
internal object ShareQr {
    fun matrix(url: String, pixels: Int): BitMatrix = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE,
        pixels, pixels, mapOf(EncodeHintType.MARGIN to 4,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.CHARACTER_SET to "UTF-8"))
}
