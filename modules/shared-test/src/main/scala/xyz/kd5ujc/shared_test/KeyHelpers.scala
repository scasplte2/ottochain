package xyz.kd5ujc.shared_test

import java.math.BigInteger
import java.security.spec.{PKCS8EncodedKeySpec, X509EncodedKeySpec}
import java.security.{KeyFactory, KeyPair}

import cats.effect.Sync
import cats.implicits.{toFlatMapOps, toFunctorOps}

import io.constellationnetwork.security.{SecurityProvider, key}

import org.bouncycastle.jce.ECNamedCurveTable
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec
import org.bouncycastle.math.ec.ECPoint

object KeyHelpers {

  def toKeyPair[F[_]: Sync: SecurityProvider](skHex: String): F[KeyPair] = {
    def derivePublicKeyHex(skHex: String): F[String] = Sync[F].delay {
      // Obtain curve parameters for secp256k1 using BouncyCastle's ECNamedCurveTable
      val curveParams: ECNamedCurveParameterSpec = ECNamedCurveTable.getParameterSpec("secp256k1")

      // Convert hex string to BigInteger for private key
      val privateKeyInt = new BigInteger(skHex, 16)

      // Calculate the public key point by multiplying the base point G with the private key integer
      val pubPoint: ECPoint = curveParams.getG.multiply(privateKeyInt).normalize()

      // Convert BouncyCastle ECPoint to uncompressed hex format
      val xCoord = pubPoint.getAffineXCoord.toBigInteger.toString(16)
      val yCoord = pubPoint.getAffineYCoord.toBigInteger.toString(16)
      "04" + xCoord + yCoord // '04' prefix indicates uncompressed format
    }

    for {
      vkHex <- derivePublicKeyHex(skHex)
      skEncodedBytes = new BigInteger(
        key.PrivateKeyHexPrefix ++ skHex ++ key.secp256kHexIdentifier ++ vkHex,
        16
      ).toByteArray
      vkEncodedBytes = new BigInteger(key.PublicKeyHexPrefix ++ vkHex.drop(2), 16).toByteArray
      skSpec <- Sync[F].delay {
        new PKCS8EncodedKeySpec(skEncodedBytes)
      }
      vkSpec <- Sync[F].delay {
        new X509EncodedKeySpec(vkEncodedBytes)
      }
      kf <- Sync[F].delay {
        KeyFactory.getInstance(key.ECDSA, SecurityProvider[F].provider)
      }
      sk <- Sync[F].delay {
        kf.generatePrivate(skSpec)
      }
      vk <- Sync[F].delay {
        kf.generatePublic(vkSpec)
      }
    } yield new KeyPair(vk, sk)
  }
}
