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
package xml

import dom.ElementBuilder
import xpath.internals._
import pfsa.{PDFA, PNFA, Pred}
import Pred.syntax._

import cats.effect.Concurrent
import cats.syntax.all._
import scala.annotation.nowarn

package object xpath {

  /** Namespace containing the various XPath filtering pipes. */
  def filter[F[_]]: PartiallyAppliedFilter[F] = new PartiallyAppliedFilter(true)

  /** Namespace containing the various XPath filtering pipes. */
  @nowarn
  final class PartiallyAppliedFilter[F[_]] private[xpath] (val dummy: Boolean) extends AnyVal {

    /** Selects all macthing elements in the input stream. Each matching element is emitted in a new stream.
      * Matching is performed in a streaming fashion, and events are emitted as early as possible.
      * The match streams are emitted in the same order they are encountered in the input stream, i.e.
      * in the order of the opening tags matching the query.
      *
      * The `maxMatch` parameter controls how many matches are to be emitted at most.
      * Further matches won't be emitted if any.
      *
      * The `maxNest` parameter controls the maximum level of match nesting to be emitted.
      * E.g., if you want to emit only the top most matches, set it to `0`.
      *
      * '''Warning''': make sure you actually consume all the emitted streams otherwise
      * this can lead to memory problems. The streams must all be consumed in parallel
      * to avoid hanging programs.
      */
    def unsafeRaw(path: XPath, maxMatch: Int = Int.MaxValue, maxNest: Int = Int.MaxValue)(implicit
        F: Concurrent[F]): Pipe[F, XmlEvent, Stream[F, XmlEvent]] =
      new XmlQueryPipe(compileXPath(path)).raw(maxMatch, maxNest)(_)

    @deprecated(message = "Use `filter.unsafeRaw()` instead", since = "fs2-data 1.12.0")
    def raw(path: XPath, maxMatch: Int = Int.MaxValue, maxNest: Int = Int.MaxValue)(implicit
        F: Concurrent[F]): Pipe[F, XmlEvent, Stream[F, XmlEvent]] =
      unsafeRaw(path = path, maxMatch = maxMatch, maxNest = maxNest)

    /** Selects the first match only. First is meant as in: opening tag appears first in the input, no matter the depth.
      * Tokens of the first match are emitted as they are read from the input.
      *
      * Other results are gently discarded.
      */
    def first(path: XPath)(implicit F: Concurrent[F]): Pipe[F, XmlEvent, XmlEvent] =
      new XmlQueryPipe(compileXPath(path)).first(_)

    /** Selects all matching elements in the input stream, and builds an element DOM.
      *
      * If `deterministic` is set to `true` (default value), elements are emitted in the order they
      * appeat in the input stream, i.e. first opening tag first.
      * If `deterministic` is set to false, built elements are emitted as soon
      * as possible (i.e. when the value is entirely built).
      *
      * The `maxMatch` parameter controls how many matches are to be emitted at most.
      * Further matches won't be emitted if any.
      *
      * The `maxNest` parameter controls the maximum level of match nesting to be emitted.
      * E.g., if you want to emit only the top most matches, set it to `0`.
      *
      */
    def dom[T](path: XPath, deterministic: Boolean = true, maxMatch: Int = Int.MaxValue, maxNest: Int = Int.MaxValue)(
        implicit
        F: Concurrent[F],
        builder: ElementBuilder.Aux[T]): Pipe[F, XmlEvent, T] =
      new XmlQueryPipe(compileXPath(path))
        .aggregate(_, _.through(xml.dom.elements).compile.toList, deterministic, maxMatch, maxNest)
        .flatMap(Stream.emits(_))

    /** Selects all matching elements in the input stream, feeding them to the provided [[fs2.Pipe]] in parallel.
      * Each match results in a new stream of [[fs2.data.xml.XmlEvent XmlEvent]] fed to the `pipe`. All the matches are processed in parallel as soon as new events are available.
      *
      * The `maxMatch` parameter controls how many matches are to be emitted at most.
      * Further matches won't be emitted if any.
      *
      * The `maxNest` parameter controls the maximum level of match nesting to be emitted.
      * E.g., if you want to emit only the top most matches, set it to `0`.
      *
      */
    def through(path: XPath,
                pipe: Pipe[F, XmlEvent, Nothing],
                maxMatch: Int = Int.MaxValue,
                maxNest: Int = Int.MaxValue)(implicit F: Concurrent[F]): Pipe[F, XmlEvent, Nothing] =
      new XmlQueryPipe(compileXPath(path)).through(_, pipe, maxMatch, maxNest)

    /** Selects all matching elements in the input stream, and applies the [[fs2.Collector]] to it.
      *
      * If `deterministic` is set to `true` (default value), elements are emitted in the order they
      * appeat in the input stream, i.e. first opening tag first.
      * If `deterministic` is set to false, built elements are emitted as soon
      * as possible (i.e. when the value is entirely built).
      *
      * The `maxMatch` parameter controls how many matches are to be emitted at most.
      * Further matches won't be emitted if any.
      *
      * The `maxNest` parameter controls the maximum level of match nesting to be emitted.
      * E.g., if you want to emit only the top most matches, set it to `0`.
      *
      */
    def collect[T](path: XPath,
                   collector: Collector.Aux[XmlEvent, T],
                   deterministic: Boolean = true,
                   maxMatch: Int = Int.MaxValue,
                   maxNest: Int = Int.MaxValue)(implicit F: Concurrent[F]): Pipe[F, XmlEvent, T] =
      new XmlQueryPipe(compileXPath(path)).aggregate(_, _.compile.to(collector), deterministic, maxMatch, maxNest)

  }

  private[data] def compileXPath(path: XPath): PDFA[LocationMatch, StartElement] = {
    def makePredicate(p: Predicate): LocationMatch =
      p match {
        case Predicate.True             => LocationMatch.True
        case Predicate.False            => LocationMatch.False
        case Predicate.Exists(attr)     => LocationMatch.AttrExists(attr)
        case Predicate.Eq(attr, value)  => LocationMatch.AttrEq(attr, value)
        case Predicate.Neq(attr, value) => LocationMatch.AttrNeq(attr, value)
        case Predicate.And(left, right) => makePredicate(left) && makePredicate(right)
        case Predicate.Or(left, right)  => makePredicate(left) || makePredicate(right)
        case Predicate.Not(inner)       => !makePredicate(inner)
      }

    def makeLocation(l: Location): LocationMatch =
      l match {
        case Location(_, n, p) =>
          val node: LocationMatch =
            n match {
              case Node(None, None) => LocationMatch.True
              case _                => LocationMatch.Element(n)
            }
          node && p.map(makePredicate(_)).getOrElse(LocationMatch.True)
      }

    val (_, transitions, fs) =
      path.locations.foldLeft((1, Map.empty[Int, List[(Option[LocationMatch], Int)]], Set.empty[Int])) {
        case ((qNext, trans, fs), ors) =>
          val (qf, q1, trans1) =
            ors.foldLeft((0, qNext, trans)) { case ((q, qNext, trans), l @ Location(axis, _, _)) =>
              axis match {
                case Axis.Child => (qNext, qNext + 1, trans.combine(Map((q -> List((Some(makeLocation(l)), qNext))))))
                case Axis.Descendant =>
                  (qNext,
                   qNext + 1,
                   trans.combine(Map(q -> List((Some(makeLocation(l)), qNext), (Some(LocationMatch.True), q)))))
              }
            }
          (q1, trans1, fs + qf)

      }
    new PNFA(0, fs, transitions).determinize
  }

}
