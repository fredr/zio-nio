package zio
package nio.core
package charset

import java.nio.charset.{ MalformedInputException, UnmappableCharacterException }

import zio.test._
import zio.test.Assertion._
import zio.stream.Stream

object CharsetSpec extends DefaultRunnableSpec {

  override def spec = suite("CharsetSpec")(
    chunkEncodeDecode(Charset.Standard.utf8),
    chunkEncodeDecode(Charset.Standard.utf16),
    bufferEncodeDecode(Charset.Standard.utf8),
    bufferEncodeDecode(Charset.Standard.utf16),
    testM("utf8 encode") {
      Charset.Standard.utf8.encodeChunk(arabicChunk).map {
        assert(_)(equalTo(arabicUtf8))
      }
    },
    streamEncodeDecode(Charset.Standard.utf8),
    streamEncodeDecode(Charset.Standard.utf16Be),
    testM("stream decode across chunk boundaries") {
      val byteStream = Stream
        .fromIterable(arabicUtf8.toSeq)
        .map(Chunk.single)
      for {
        chars <- Charset.Standard.utf8.newDecoder.decodeStream(byteStream).run(chunkCollectSink[Char])
      } yield assert(chars)(equalTo(arabicChunk))
    },
    testM("minimum buffer size for encoding") {
      val in = Stream.succeed(arabicChunk)
      val s  = Charset.Standard.utf8.newEncoder.encodeStream(in, 49)
      assertM(s.runDrain.run)(dies(isSubtype[IllegalArgumentException](anything)))
    },
    testM("minimum buffer size for decoding") {
      val in = Stream.succeed(arabicUtf8)
      val s  = Charset.Standard.utf8.newDecoder.decodeStream(in, 49)
      assertM(s.runDrain.run)(dies(isSubtype[IllegalArgumentException](anything)))
    },
    testM("handles encoding errors") {
      val in = Stream.succeed(arabicChunk)
      val s  = Charset.Standard.iso8859_1.newEncoder.encodeStream(in)
      assertM(s.runDrain.run)(fails(isSubtype[UnmappableCharacterException](anything)))
    },
    testM("handles decoding errors") {
      val in = Stream(0xd8, 0x00, 0xa5, 0xd8).map(i => Chunk(i.toByte))
      val s  = Charset.Standard.utf16Le.newDecoder.decodeStream(in)
      assertM(s.runDrain.run)(fails(isSubtype[MalformedInputException](anything)))
    }
  )

  val arabic = "إزَّي حضرتك؟"

  val arabicChunk = Chunk.fromIterable(arabic)

  val arabicUtf8 = Chunk(0xd8, 0xa5, 0xd8, 0xb2, 0xd9, 0x91, 0xd9, 0x8e, 0xd9, 0x8a, 0x20, 0xd8, 0xad, 0xd8, 0xb6, 0xd8,
    0xb1, 0xd8, 0xaa, 0xd9, 0x83, 0xd8, 0x9f)
    .map(_.toByte)

  def chunkEncodeDecode(charset: Charset) = testM(s"chunk encode/decode ${charset.displayName}") {
    for {
      encoded <- charset.encodeChunk(arabicChunk)
      decoded <- charset.decodeChunk(encoded)
    } yield assert(decoded)(equalTo(arabicChunk))
  }

  def bufferEncodeDecode(charset: Charset) = testM(s"buffer encode/decode ${charset.displayName}") {
    for {
      chars             <- Buffer.char(100).orDie
      _                 <- chars.putChunk(arabicChunk).orDie
      _                 <- chars.flip
      bytes             <- charset.encode(chars)
      charsHasRemaining <- chars.hasRemaining
      decoded           <- charset.decode(bytes)
      chunk             <- decoded.getChunk().orDie
    } yield assert(charsHasRemaining)(isFalse) && assert(chunk)(equalTo(arabicChunk))
  }

  def streamEncodeDecode(charset: Charset) = testM(s"stream encode/decode ${charset.displayName}") {
    val charStream = Stream.fromIterable(arabic).grouped(2).map(Chunk.fromIterable)
    for {
      byteChunks <- charset.newEncoder.encodeStream(charStream).runCollect
      byteStream = Stream.fromIterable(byteChunks)
      chars      <- charset.newDecoder.decodeStream(byteStream).run(chunkCollectSink[Char])
    } yield assert(chars)(equalTo(arabicChunk))
  }
}
