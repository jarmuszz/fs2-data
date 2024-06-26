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

import low.internal.ItemParser

/** A low-level representation of the MessagePack format.
  */
package object low {
  def items[F[_]](implicit F: RaiseThrowable[F]): Pipe[F, Byte, MsgpackItem] =
    ItemParser.pipe[F]
}
