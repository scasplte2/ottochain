package xyz.kd5ujc.schema.fiber

import java.nio.ByteBuffer
import java.util.UUID

/**
 * Deterministic, offline, checksummed mnemonic fingerprint of a fiber UUID
 * (`fingerprintScheme = v1-proquint`).
 *
 * A pure function of the UUID — no registry, computable by anyone (human, agent, light client),
 * stable forever. Used to render human-readable audit trails for fibers that have no chosen nickname
 * (see docs/proposals/naming-and-fingerprints.md). The scheme is a fixed protocol asset: changing it
 * re-labels every fiber, so additional schemes are added as new versions rather than edited in place.
 *
 * Encoding (Wilkerson proquint): each 16-bit group becomes a pronounceable 5-char quintet
 * consonant-vowel-consonant-vowel-consonant, consonants drawn from `bdfghjklmnprstvz` (4 bits each) and
 * vowels from `aiou` (2 bits each). A 128-bit UUID yields 8 data quintets; a CRC-16/CCITT checksum over
 * the 16 UUID bytes is appended as a 9th quintet so a mistyped fingerprint is detectable rather than
 * silently resolving to the wrong fiber. The data quintets are bijective, so the UUID is recoverable via
 * [[decode]]; the fiber kind ("machine"/"script") is appended as a TLD-like suffix.
 *
 * Example: `lusab-babad-gutih-tugad-fadih-rinov-kanut-zalum-bavor.machine`
 */
object FiberFingerprint {

  /** Identifier of this fingerprint scheme (additional schemes are added as new versions). */
  final val Scheme: String = "v1-proquint"

  private val Consonants: Array[Char] = "bdfghjklmnprstvz".toCharArray // 16 -> 4 bits
  private val Vowels: Array[Char] = "aiou".toCharArray // 4  -> 2 bits

  private val DataQuintets = 8 // 128 UUID bits / 16
  private val TotalQuintets = DataQuintets + 1 // + checksum

  /** Fingerprint without the kind suffix, e.g. `lusab-...-bavor`. */
  def encode(uuid: UUID): String = {
    val bytes = toBytes(uuid)
    val data = (0 until DataQuintets).map(i => quintet(group16(bytes, i)))
    val cksum = quintet(crc16(bytes))
    (data :+ cksum).mkString("-")
  }

  /** Fingerprint with the fiber-kind TLD suffix, e.g. `lusab-...-bavor.machine`. */
  def of(uuid: UUID, kind: FiberKind): String = s"${encode(uuid)}.${tld(kind)}"

  def tld(kind: FiberKind): String = kind match {
    case FiberKind.StateMachine => "machine"
    case FiberKind.Script       => "script"
  }

  /**
   * Recover the UUID from a fingerprint (with or without a kind suffix), verifying the checksum.
   * Returns Left with a reason if the shape, alphabet, or checksum is invalid.
   */
  def decode(fingerprint: String): Either[String, UUID] = {
    val core = fingerprint.takeWhile(_ != '.')
    val parts = core.split('-').toList
    if (parts.length != TotalQuintets)
      Left(s"expected $TotalQuintets quintets ($DataQuintets data + checksum), got ${parts.length}")
    else {
      val parsed = parts.map(unquintet)
      parsed.collectFirst { case Left(e) => e } match {
        case Some(err) => Left(err)
        case None =>
          val all = parsed.collect { case Right(n) => n }
          val data = all.take(DataQuintets)
          val cksum = all(DataQuintets)
          val bytes = new Array[Byte](16)
          data.zipWithIndex.foreach { case (n, i) =>
            bytes(2 * i) = ((n >> 8) & 0xff).toByte
            bytes(2 * i + 1) = (n & 0xff).toByte
          }
          if (crc16(bytes) != cksum) Left("checksum mismatch")
          else {
            val bb = ByteBuffer.wrap(bytes)
            Right(new UUID(bb.getLong, bb.getLong))
          }
      }
    }
  }

  // ── internals ──────────────────────────────────────────────────────────────

  private def toBytes(uuid: UUID): Array[Byte] = {
    val bb = ByteBuffer.allocate(16)
    bb.putLong(uuid.getMostSignificantBits)
    bb.putLong(uuid.getLeastSignificantBits)
    bb.array()
  }

  private def group16(bytes: Array[Byte], i: Int): Int =
    ((bytes(2 * i) & 0xff) << 8) | (bytes(2 * i + 1) & 0xff)

  private def quintet(n: Int): String =
    new String(
      Array(
        Consonants((n >> 12) & 0xf),
        Vowels((n >> 10) & 0x3),
        Consonants((n >> 6) & 0xf),
        Vowels((n >> 4) & 0x3),
        Consonants(n & 0xf)
      )
    )

  private def unquintet(s: String): Either[String, Int] =
    if (s.length != 5) Left(s"invalid quintet '$s': expected 5 chars")
    else {
      val c1 = Consonants.indexOf(s.charAt(0))
      val v1 = Vowels.indexOf(s.charAt(1))
      val c2 = Consonants.indexOf(s.charAt(2))
      val v2 = Vowels.indexOf(s.charAt(3))
      val c3 = Consonants.indexOf(s.charAt(4))
      if (c1 < 0 || v1 < 0 || c2 < 0 || v2 < 0 || c3 < 0) Left(s"invalid quintet '$s'")
      else Right((c1 << 12) | (v1 << 10) | (c2 << 6) | (v2 << 4) | c3)
    }

  /** CRC-16/CCITT-FALSE (poly 0x1021, init 0xFFFF) over the raw bytes. */
  private def crc16(bytes: Array[Byte]): Int = {
    var crc = 0xffff
    bytes.foreach { b =>
      crc ^= (b & 0xff) << 8
      var i = 0
      while (i < 8) {
        crc = if ((crc & 0x8000) != 0) ((crc << 1) ^ 0x1021) & 0xffff else (crc << 1) & 0xffff
        i += 1
      }
    }
    crc & 0xffff
  }
}
