package dev.lemonadecode.mobile.impl

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import dev.lemonadecode.domain.mobile.QrMatrix
import dev.lemonadecode.platform.di.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject

/** QR codes from ZXing, at error correction level M and without a quiet zone: the UI draws the margin. */
@ContributesBinding(AppScope::class)
@Inject
internal class ZxingQrEncoder : QrEncoder {
  override fun encode(text: String): QrMatrix {
    val matrix = Encoder.encode(text, ErrorCorrectionLevel.M).matrix
    val size = matrix.width
    return QrMatrix(size, BooleanArray(size * size) { matrix.get(it % size, it / size).toInt() == 1 })
  }
}
