/*
 * Copyright 2019 Lucas Satabin
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
package fs2.data

import fs2._

import cats._
import cats.data._
import cats.implicits._

import scala.annotation.tailrec

import scala.language.higherKinds

package object csv {

  private sealed trait State
  private object State {
    case object BeginningOfField extends State
    case object InQuoted extends State
    case object InQuotedSeenQuote extends State
    case object InUnquoted extends State
    case object InUnquotedSeenCr extends State
    case object ExpectNewLine extends State
  }

  private case class ParseEnv(currentField: StringBuilder, tail: List[String], state: State, idx: Int)

  def rows[F[_]](separator: Char = ',')(implicit F: MonadError[F, Throwable]): Pipe[F, String, NonEmptyList[String]] = {

    def row(chunk: Chunk[Char],
            currentField: StringBuilder,
            tail: List[String],
            state: State,
            idx: Int): Pull[F, NonEmptyList[String], ParseEnv] = {

      def loop(currentField: StringBuilder,
               tail: List[String],
               state: State,
               idx: Int): Pull[F, NonEmptyList[String], ParseEnv] =
        if (idx >= chunk.size) {
          Pull.pure(ParseEnv(currentField, tail, state, 0))
        } else {
          val c = chunk(idx)
          state match {
            case State.InQuoted =>
              // only handle quote specially
              if (c == '"') {
                loop(currentField, tail, State.InQuotedSeenQuote, idx + 1)
              } else {
                loop(currentField.append(c), tail, State.InQuoted, idx + 1)
              }
            case State.InQuotedSeenQuote =>
              if (c == '"') {
                loop(currentField.append(c), tail, State.InQuoted, idx + 1)
              } else if (c == separator) {
                // end of quoted field, go to next
                val field = currentField.result
                currentField.clear
                loop(currentField, field :: tail, State.BeginningOfField, idx + 1)
              } else if (c == '\n') {
                val field = currentField.result
                currentField.clear
                Pull.output1(NonEmptyList(field, tail).reverse) >> loop(currentField,
                                                                        Nil,
                                                                        State.BeginningOfField,
                                                                        idx + 1)
              } else if (c == '\r') {
                loop(currentField, tail, State.ExpectNewLine, idx + 1)
              } else {
                // this is an error
                Pull.raiseError[F](new CsvException(s"unexpected character '$c'"))
              }
            case State.ExpectNewLine =>
              if (c == '\n') {
                val field = currentField.result
                currentField.clear
                Pull.output1(NonEmptyList(field, tail).reverse) >> loop(currentField,
                                                                        Nil,
                                                                        State.BeginningOfField,
                                                                        idx + 1)
              } else {
                // this is an error
                Pull.raiseError[F](new CsvException(s"unexpected character '$c'"))
              }
            case State.BeginningOfField =>
              if (c == '"') {
                // start a quoted field
                loop(currentField, tail, State.InQuoted, idx + 1)
              } else if (c == separator) {
                // this is an empty field
                loop(currentField, "" :: tail, State.BeginningOfField, idx + 1)
              } else if (c == '\n') {
                // a new line, emit row if not empty and continue
                if (tail.nonEmpty) {
                  Pull.output1(NonEmptyList("", tail).reverse) >> loop(currentField,
                                                                       Nil,
                                                                       State.BeginningOfField,
                                                                       idx + 1)
                } else {
                  loop(currentField, Nil, State.BeginningOfField, idx + 1)
                }
              } else if (c == '\r') {
                loop(currentField, tail, State.InUnquotedSeenCr, idx + 1)
              } else {
                loop(currentField.append(c), tail, State.InUnquoted, idx + 1)
              }
            case State.InUnquoted =>
              if (c == separator) {
                // this is the end of the field, not the row
                val field = currentField.result
                currentField.clear
                loop(currentField, field :: tail, State.BeginningOfField, idx + 1)
              } else if (c == '\n') {
                // a new line, emit row and continue
                val field = currentField.result
                currentField.clear
                Pull.output1(NonEmptyList(field, tail).reverse) >> loop(currentField,
                                                                        Nil,
                                                                        State.BeginningOfField,
                                                                        idx + 1)
              } else if (c == '\r') {
                loop(currentField, tail, State.InUnquotedSeenCr, idx + 1)
              } else {
                loop(currentField.append(c), tail, State.InUnquoted, idx + 1)
              }
            case State.InUnquotedSeenCr =>
              if (c == '\n') {
                // a new line, emit row if not empty and continue
                val field = currentField.result
                currentField.clear
                Pull.output1(NonEmptyList(field, tail).reverse) >> loop(currentField,
                                                                        Nil,
                                                                        State.BeginningOfField,
                                                                        idx + 1)
              } else {
                currentField.append('\r')
                if (c == separator) {
                  // this is the end of the field, not the row
                  val field = currentField.result
                  currentField.clear
                  loop(currentField, field :: tail, State.BeginningOfField, idx + 1)
                } else {
                  // continue parsing field
                  currentField.append(c)
                  loop(currentField, tail, State.InUnquoted, idx + 1)
                }
              }
          }
        }
      loop(currentField, tail, state, idx)
    }

    def go(s: Stream[F, Char], env: ParseEnv): Pull[F, NonEmptyList[String], Unit] =
      s.pull.uncons.flatMap {
        case Some((c, rest)) =>
          row(c, env.currentField, env.tail, env.state, env.idx).flatMap(go(rest, _))
        case None =>
          // we're done parsing, emit potential last line
          env.state match {
            case State.BeginningOfField =>
              if (env.tail.nonEmpty)
                Pull.output1(NonEmptyList("", env.tail).reverse) >> Pull.done
              else
                Pull.done
            case State.InUnquoted | State.InQuotedSeenQuote | State.ExpectNewLine =>
              Pull.output1(NonEmptyList(env.currentField.result, env.tail).reverse) >> Pull.done
            case State.InUnquotedSeenCr =>
              Pull.output1(NonEmptyList(env.currentField.append('\r').result, env.tail).reverse) >> Pull.done
            case State.InQuoted =>
              Pull.raiseError[F](new CsvException("unexpected end of input"))
          }
      }

    s => go(s.flatMap(Stream.emits(_)), ParseEnv(new StringBuilder, Nil, State.BeginningOfField, 0)).stream
  }

  def fromBytes[F[_]](separator: Char = ',')(implicit F: MonadError[F, Throwable]): ToByteCsvPipe[F] =
    new ToByteCsvPipe[F](separator)

  def fromString[F[_]](separator: Char = ',')(implicit F: MonadError[F, Throwable]): ToStringCsvPipe[F] =
    new ToStringCsvPipe[F](separator)

}
