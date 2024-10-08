/*
 * Copyright 2024 fs2-data Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fs2
package data
package msgpack
package low
package internal

import scodec.bits.ByteVector

private[internal] object Helpers {

  /** @param chunk Current chunk
    * @param idx Index of the current [[Byte]] in `chunk`
    * @param rest Rest of the stream
    * @param acc Accumulator of which contents are emitted when acquiring a new chunk 
    */
  case class ParserContext[F[_]](chunk: Chunk[Byte], idx: Int, rest: Stream[F, Byte], acc: List[MsgpackItem]) {
    def prepend(item: MsgpackItem) = ParserContext(chunk, idx, rest, item :: acc)
    def next = ParserContext(chunk, idx + 1, rest, acc)
    def toResult[T](result: T) = ParserResult(chunk, idx, rest, acc, result)
  }

  case class ParserResult[F[_], T](chunk: Chunk[Byte],
                                   idx: Int,
                                   rest: Stream[F, Byte],
                                   acc: List[MsgpackItem],
                                   result: T) {
    def toContext = ParserContext(chunk, idx, rest, acc)
    def accumulate(op: T => MsgpackItem) = ParserContext(chunk, idx, rest, op(result) :: acc)
  }

  /** Ensures that a computation `cont` will happen inside a valid context.
    * @param cont function to be run with a chunk ensured
    * @param onEos ran when out of stream
    */
  def ensureChunk[F[_], T](ctx: ParserContext[F])(cont: ParserContext[F] => Pull[F, MsgpackItem, T])(
      onEos: => Pull[F, MsgpackItem, T]): Pull[F, MsgpackItem, T] = {
    if (ctx.idx >= ctx.chunk.size) {
      Pull.output(Chunk.from(ctx.acc.reverse)) >> ctx.rest.pull.uncons.flatMap {
        case Some((hd, tl)) => ensureChunk(ParserContext(hd, 0, tl, Nil))(cont)(onEos)
        case None           => onEos
      }
    } else {
      cont(ctx)
    }
  }

  def requireOneByte[F[_]](ctx: ParserContext[F])(implicit
      F: RaiseThrowable[F]): Pull[F, MsgpackItem, ParserResult[F, Byte]] = {
    ensureChunk(ctx) { ctx =>
      // Inbounds chunk access is guaranteed by `ensureChunk`
      Pull.pure(ctx.next.toResult(ctx.chunk(ctx.idx)))
    } {
      Pull.raiseError(MsgpackUnexpectedEndOfStreamException())
    }
  }

  def requireBytes[F[_]](count: Int, ctx: ParserContext[F])(implicit
      F: RaiseThrowable[F]): Pull[F, MsgpackItem, ParserResult[F, ByteVector]] = {
    def go(count: Int, ctx: ParserContext[F], bytes: ByteVector): Pull[F, MsgpackItem, ParserResult[F, ByteVector]] = {
      ensureChunk(ctx) { case ParserContext(chunk, idx, rest, acc) =>
        // Array slice has O(1) `drop` and `take`.
        val slice = chunk.toArraySlice

        // How much chunk do we have left.
        val available = slice.size - idx

        // We accumulate either what is available, or the count.
        val accumulated = Math.min(count, available)
        val newBytes = bytes ++ slice.drop(idx).take(accumulated).toByteVector

        if (available >= count) {
          // We have enough bytes
          Pull.pure(ParserResult(slice, idx + count, rest, acc, newBytes))
        } else {
          // Too short, append current bytes and continue.
          go(count - available, ParserContext(chunk, slice.size, rest, acc), newBytes)
        }
      } {
        Pull.raiseError(MsgpackUnexpectedEndOfStreamException())
      }
    }

    if (count <= 0) {
      Pull.pure(ctx.toResult(ByteVector.empty))
    } else {
      go(count, ctx, ByteVector.empty)
    }
  }
}
