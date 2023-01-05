/*
 * Copyright 2019 http4s.org
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

package org.http4s.ember.server.internal

import cats.effect._
import cats.effect.implicits._
import cats.syntax.all._
import fs2.Stream

import scala.concurrent.duration.Duration
import scala.concurrent.duration.FiniteDuration

private[server] abstract class Shutdown[F[_]] {
  def doShutdown:             F[Unit]
  def getShutdownStartSignal: F[Unit]
  def newConnection:          F[Unit]
  def removeConnection:       F[Unit]

  def trackConnection: Stream[F, Unit] = Stream.bracket(newConnection)(_ => removeConnection)
}

private[server] object Shutdown {

  def apply[F[_]](timeout: Duration)(implicit F: Temporal[F]): F[Shutdown[F]] =
    timeout match {
      case fi: FiniteDuration => if (fi.length == 0) immediateShutdown else timedShutdown(timeout)
      case _                  => timedShutdown(timeout)
    }

  private def timedShutdown[F[_]](gracePeriod: Duration)(implicit F: Temporal[F]): F[Shutdown[F]] = {
    case class State(isShutdown: Boolean, activeSocketCnt: Int)

    for {
      startSignal  <- Deferred[F, Unit]
      finishSignal <- Deferred[F, Unit]
      stateF       <- Ref.of[F, State](State(isShutdown = false, 0))
    } yield new Shutdown[F] {
      override val doShutdown: F[Unit] =
        startSignal
          .complete(())
          .flatMap { _ =>
            stateF.modify { state: State =>
              val shutdownFinishedF =
                if (state.activeSocketCnt == 0) F.unit // No active connections
                else {
                  gracePeriod match {
                    case fi: FiniteDuration => finishSignal.get.timeoutTo(fi, F.unit)
                    case _                  => finishSignal.get
                  }
                }
              state.copy(isShutdown = true) -> shutdownFinishedF
            }
          }
          .uncancelable
          .flatten

      override val getShutdownStartSignal: F[Unit] = startSignal.get

      override val newConnection: F[Unit] =
        stateF.update { s =>
          s.copy(activeSocketCnt = s.activeSocketCnt + 1)
        }

      override val removeConnection: F[Unit] =
        stateF
          .modify { state =>
            val conns = state.activeSocketCnt - 1
            if (state.isShutdown && conns <= 0) {
              state.copy(activeSocketCnt = conns) -> finishSignal.complete(()).void
            } else {
              state.copy(activeSocketCnt = conns) -> F.unit
            }
          }
          .flatten
          .uncancelable
    }
  }

  private def immediateShutdown[F[_]](implicit F: Concurrent[F]): F[Shutdown[F]] =
    Deferred[F, Unit].map { unblock: Deferred[F, Unit] =>
      new Shutdown[F] {
        override val doShutdown: F[Unit]              = unblock.complete(()).void
        override val getShutdownStartSignal: F[Unit]  = unblock.get
        override val newConnection: F[Unit]           = F.unit
        override val removeConnection: F[Unit]        = F.unit
        override val trackConnection: Stream[F, Unit] = Stream.empty
      }
    }

}
